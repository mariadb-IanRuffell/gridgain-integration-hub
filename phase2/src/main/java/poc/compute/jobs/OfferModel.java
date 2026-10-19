package poc.compute.jobs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The offer recommender: no I/O, no cluster, so it can be tested on its own. The job feeds it one customer's purchase history and the
 * product catalogue and gets back ranked, priced offers with a plain-language reason for each.
 *
 * Score of a product for a customer = (affinity + 0.8 * peer + repurchase + trip) * priceFit
 *  - affinity: share of the customer's spend that went to the product's category, each purchase decayed with a 90-day half-life
 *  - peer: share of customers in the same loyalty tier who have bought the product ("people like you"); damped for products the customer has
 *    already bought, since the repurchase signal covers those
 *  - repurchase: consumable products (lounge, insurance, ancillaries) the customer bought before, but not in the last 30 days
 *  - trip: the customer's next confirmed flight (Oracle travel data): hotels, lounge, insurance, ancillaries and upgrades are boosted for a trip
 *    in the next 45 days; if that flight is delayed 3+ hours or cancelled, only a lounge offer is boosted (goodwill)
 *  - priceFit: products costing more than twice the customer's average unit price are damped, so offers stay plausible
 * Products that are inactive, out of stock or were bought in the last 30 days are never offered, at most two offers per category.
 * Discount = loyalty tier (0/5/8/10 %) + churn risk (0/3/7 %) + 5 % when the customer's flight was disrupted, capped at 20 %.
 */
final class OfferModel {
    static final double HALF_LIFE_DAYS = 90, PEER_WEIGHT = 0.8, REPURCHASE_BONUS = 0.5, TRIP_BONUS = 0.7, GOODWILL_BONUS = 0.9, MIN_SCORE = 0.05;
    static final int RECENT_PURCHASE_DAYS = 30, TRIP_WINDOW_DAYS = 45, PER_CATEGORY_CAP = 2, MAX_DISCOUNT_PCT = 20, VALID_DAYS = 14;
    static final Set<String> CONSUMABLE = Set.of("LOUNGE", "INSURANCE", "ANCILLARY");
    static final Set<String> TRIP_CATEGORIES = Set.of("HOTEL", "LOUNGE", "INSURANCE", "ANCILLARY", "UPGRADE");
    private static final long DAY_MS = 86_400_000L;

    private OfferModel() {}

    static final class Product {
        final int id;
        final String sku, name, category;
        final BigDecimal price;
        final int stock;
        final boolean active;

        Product(int id, String sku, String name, String category, BigDecimal price, int stock, boolean active) {
            this.id = id; this.sku = sku; this.name = name; this.category = category; this.price = price; this.stock = stock; this.active = active;
        }
    }

    /** One customer's completed purchases of one product. */
    static final class Purchase {
        final int productId;
        final String category;
        final long qty;
        final BigDecimal spend;
        final long lastMs;

        Purchase(int productId, String category, long qty, BigDecimal spend, long lastMs) {
            this.productId = productId; this.category = category; this.qty = qty; this.spend = spend; this.lastMs = lastMs;
        }
    }

    /** The customer's next confirmed flight. */
    static final class Trip {
        final String flightNo, origin, destination;
        final long depMs;
        final boolean disrupted;

        Trip(String flightNo, String origin, String destination, long depMs, boolean disrupted) {
            this.flightNo = flightNo; this.origin = origin; this.destination = destination; this.depMs = depMs; this.disrupted = disrupted;
        }
    }

    static final class Profile {
        final int customerId;
        final String tier, riskBand;     // riskBand may be null (not scored yet)
        final List<Purchase> purchases;
        final Trip trip;                 // may be null

        Profile(int customerId, String tier, String riskBand, List<Purchase> purchases, Trip trip) {
            this.customerId = customerId; this.tier = tier; this.riskBand = riskBand; this.purchases = purchases; this.trip = trip;
        }
    }

    static final class Offer {
        final int customerId, rank, productId, discountPct;
        final String sku, productName, category, reason;
        final double score;
        final BigDecimal listPrice, offerPrice;
        final long generatedMs, expiresMs;

        Offer(int customerId, int rank, Product p, double score, int discountPct, BigDecimal offerPrice, String reason, long nowMs) {
            this.customerId = customerId; this.rank = rank; this.productId = p.id; this.sku = p.sku; this.productName = p.name;
            this.category = p.category; this.score = score; this.discountPct = discountPct; this.listPrice = p.price; this.offerPrice = offerPrice;
            this.reason = reason; this.generatedMs = nowMs; this.expiresMs = nowMs + VALID_DAYS * DAY_MS;
        }
    }

    static int tierDiscount(String tier) {
        return "PLATINUM".equals(tier) ? 10 : "GOLD".equals(tier) ? 8 : "SILVER".equals(tier) ? 5 : 0;
    }

    static int riskDiscount(String band) {
        return "HIGH".equals(band) ? 7 : "MEDIUM".equals(band) ? 3 : 0;
    }

    static int discountPct(String tier, String riskBand, boolean disrupted) {
        return Math.min(MAX_DISCOUNT_PCT, tierDiscount(tier) + riskDiscount(riskBand) + (disrupted ? 5 : 0));
    }

    static BigDecimal price(BigDecimal list, int discountPct) {
        return list.multiply(BigDecimal.valueOf(100 - discountPct)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static final class Candidate {
        final Product product;
        final double score, affinity, peerShare, repurchase, trip;

        Candidate(Product product, double score, double affinity, double peerShare, double repurchase, double trip) {
            this.product = product; this.score = score; this.affinity = affinity; this.peerShare = peerShare; this.repurchase = repurchase; this.trip = trip;
        }
    }

    /**
     * @param peers tier -> product id -> share (0..1) of that tier's customers who have bought the product
     * @return up to topN offers, best first, ranks starting at 1
     */
    static List<Offer> recommend(Profile c, List<Product> catalogue, Map<String, Map<Integer, Double>> peers, long nowMs, int topN) {
        Map<Integer, Purchase> bought = new HashMap<>();
        Map<String, Double> categoryWeight = new HashMap<>();
        double total = 0, spendSum = 0, qtySum = 0;
        for (Purchase p : c.purchases) {
            bought.put(p.productId, p);
            double ageDays = Math.max(0, (nowMs - p.lastMs) / (double) DAY_MS);
            double w = p.spend.doubleValue() * Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
            categoryWeight.merge(p.category, w, Double::sum);
            total += w;
            spendSum += p.spend.doubleValue();
            qtySum += p.qty;
        }
        double avgUnit = qtySum > 0 ? spendSum / qtySum : 0;
        Map<Integer, Double> tierPeers = peers.getOrDefault(c.tier, Collections.emptyMap());

        List<Candidate> ranked = new ArrayList<>();
        for (Product p : catalogue) {
            if (!p.active || p.stock <= 0) continue;
            Purchase pu = bought.get(p.id);
            double sinceDays = pu == null ? Double.MAX_VALUE : (nowMs - pu.lastMs) / (double) DAY_MS;
            if (sinceDays < RECENT_PURCHASE_DAYS) continue;

            double affinity = total > 0 ? categoryWeight.getOrDefault(p.category, 0.0) / total : 0;
            double peerShare = tierPeers.getOrDefault(p.id, 0.0);
            double peer = pu == null ? peerShare : peerShare * 0.3;
            double repurchase = pu != null && CONSUMABLE.contains(p.category) ? REPURCHASE_BONUS : 0;
            double trip = 0;
            if (c.trip != null) {
                if (c.trip.disrupted) {
                    if ("LOUNGE".equals(p.category)) trip = GOODWILL_BONUS;
                } else if ((c.trip.depMs - nowMs) / (double) DAY_MS <= TRIP_WINDOW_DAYS && TRIP_CATEGORIES.contains(p.category)) {
                    trip = TRIP_BONUS;
                }
            }
            double ratio = avgUnit > 0 ? p.price.doubleValue() / avgUnit : 0;
            double fit = ratio > 2 ? 2 / ratio : 1;
            double score = (affinity + PEER_WEIGHT * peer + repurchase + trip) * fit;
            if (score < MIN_SCORE) continue;
            ranked.add(new Candidate(p, score, affinity, peerShare, repurchase, trip));
        }
        ranked.sort(Comparator.<Candidate>comparingDouble(x -> -x.score).thenComparingInt(x -> x.product.id));

        int pct = discountPct(c.tier, c.riskBand, c.trip != null && c.trip.disrupted);
        Map<String, Integer> perCategory = new HashMap<>();
        Set<Integer> seen = new HashSet<>();
        List<Offer> out = new ArrayList<>();
        for (Candidate x : ranked) {
            if (out.size() >= topN) break;
            if (perCategory.merge(x.product.category, 1, Integer::sum) > PER_CATEGORY_CAP) continue;
            if (!seen.add(x.product.id)) continue;
            out.add(new Offer(c.customerId, out.size() + 1, x.product, Math.round(x.score * 10_000) / 10_000.0, pct,
                price(x.product.price, pct), reason(c, x, bought.get(x.product.id), nowMs), nowMs));
        }
        return out;
    }

    /** The two strongest signals, in words. */
    private static String reason(Profile c, Candidate x, Purchase previous, long nowMs) {
        List<double[]> parts = new ArrayList<>();           // {strength, kind}
        parts.add(new double[] {x.trip, 0});
        parts.add(new double[] {x.affinity, 1});
        parts.add(new double[] {PEER_WEIGHT * (previous == null ? x.peerShare : x.peerShare * 0.3), 2});
        parts.add(new double[] {x.repurchase, 3});
        parts.sort((a, b) -> Double.compare(b[0], a[0]));
        List<String> words = new ArrayList<>();
        for (double[] p : parts) {
            if (p[0] <= 0.05 || words.size() == 2) continue;
            switch ((int) p[1]) {
                case 0:
                    words.add(c.trip.disrupted
                        ? "goodwill after the disruption to flight " + c.trip.flightNo
                        : "you fly " + c.trip.origin + "-" + c.trip.destination + " on " + Instant.ofEpochMilli(c.trip.depMs).atZone(ZoneOffset.UTC).toLocalDate());
                    break;
                case 1:
                    words.add(Math.round(x.affinity * 100) + "% of your recent spend is on " + x.product.category.toLowerCase());
                    break;
                case 2:
                    words.add(Math.round(x.peerShare * 100) + "% of " + c.tier + " members buy this");
                    break;
                default:
                    words.add("you last bought it " + Math.round((nowMs - previous.lastMs) / (double) DAY_MS) + " days ago");
            }
        }
        return words.isEmpty() ? "recommended for you" : String.join("; ", words);
    }
}
