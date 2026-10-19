package poc.compute.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import poc.compute.jobs.OfferModel.*;

class OfferModelTest {
    static final long NOW = 1_792_000_000_000L;           // fixed "now"
    static final long DAY = 86_400_000L;

    static Product product(int id, String category, String price) {
        return new Product(id, category.substring(0, 3) + "-" + id, category + " " + id, category, new BigDecimal(price), 100, true);
    }

    /** One product per interesting category plus a second hotel and flight. */
    static List<Product> catalogue() {
        return List.of(product(1, "FLIGHT", "1200"), product(2, "FLIGHT", "5800"), product(7, "HOTEL", "650"), product(8, "HOTEL", "1100"),
            product(11, "LOUNGE", "220"), product(13, "UPGRADE", "450"), product(16, "INSURANCE", "120"), product(19, "ANCILLARY", "200"));
    }

    static Purchase bought(int productId, String category, long qty, String spend, long daysAgo) {
        return new Purchase(productId, category, qty, new BigDecimal(spend), NOW - daysAgo * DAY);
    }

    static Profile profile(String tier, String risk, Trip trip, Purchase... purchases) {
        return new Profile(7, tier, risk, List.of(purchases), trip);
    }

    static final Map<String, Map<Integer, Double>> NO_PEERS = Collections.emptyMap();

    static List<Integer> ids(List<Offer> offers) {
        return offers.stream().map(o -> o.productId).collect(Collectors.toList());
    }

    // ---- pricing

    @ParameterizedTest
    @CsvSource({"BLUE,0", "SILVER,5", "GOLD,8", "PLATINUM,10", "UNKNOWN,0"})
    void tierDiscounts(String tier, int pct) {
        assertEquals(pct, OfferModel.tierDiscount(tier));
    }

    @ParameterizedTest
    @CsvSource({"LOW,0", "MEDIUM,3", "HIGH,7", "NULL,0"})
    void riskDiscounts(String band, int pct) {
        assertEquals(pct, OfferModel.riskDiscount("NULL".equals(band) ? null : band));
    }

    @Test
    void discountAddsUpAndIsCapped() {
        assertEquals(0, OfferModel.discountPct("BLUE", "LOW", false));
        assertEquals(10, OfferModel.discountPct("GOLD", "MEDIUM", false) - 1);       // 8 + 3 = 11
        assertEquals(17, OfferModel.discountPct("PLATINUM", "HIGH", false));
        assertEquals(20, OfferModel.discountPct("PLATINUM", "HIGH", true));          // 10 + 7 + 5 = 22 capped
        assertEquals(5, OfferModel.discountPct("BLUE", "LOW", true));
    }

    @Test
    void offerPriceRoundsHalfUpToCents() {
        assertEquals(new BigDecimal("1080.00"), OfferModel.price(new BigDecimal("1200"), 10));
        assertEquals(new BigDecimal("1200.00"), OfferModel.price(new BigDecimal("1200"), 0));
        assertEquals(new BigDecimal("0.84"), OfferModel.price(new BigDecimal("0.99"), 15));    // 0.8415
        assertEquals(new BigDecimal("0.50"), OfferModel.price(new BigDecimal("0.55"), 10));    // 0.495 -> 0.50
    }

    // ---- scoring

    @Test
    void anInactiveOrOutOfStockProductIsNeverOffered() {
        List<Product> cat = List.of(new Product(1, "A", "a", "HOTEL", BigDecimal.TEN, 5, false), new Product(2, "B", "b", "HOTEL", BigDecimal.TEN, 0, true),
            new Product(3, "C", "c", "HOTEL", BigDecimal.TEN, 5, true));
        Map<String, Map<Integer, Double>> peers = Map.of("BLUE", Map.of(1, 0.9, 2, 0.9, 3, 0.9));
        assertEquals(List.of(3), ids(OfferModel.recommend(profile("BLUE", "LOW", null), cat, peers, NOW, 5)));
    }

    @Test
    void aRecentPurchaseIsNotOfferedAgainButAnOldOneMayBe() {
        Map<String, Map<Integer, Double>> peers = Map.of("BLUE", Map.of(11, 0.5));
        assertTrue(ids(OfferModel.recommend(profile("BLUE", "LOW", null, bought(11, "LOUNGE", 1, "220", 29)), catalogue(), peers, NOW, 5)).stream().noneMatch(i -> i == 11));
        assertTrue(ids(OfferModel.recommend(profile("BLUE", "LOW", null, bought(11, "LOUNGE", 1, "220", 31)), catalogue(), peers, NOW, 5)).contains(11));
    }

    @Test
    void categoryAffinityDrivesTheRanking() {
        // all spend on hotels: only hotels score (no peers, no trip), and a hotel bought 60 days ago may be offered again
        List<Offer> offers = OfferModel.recommend(profile("BLUE", "LOW", null, bought(7, "HOTEL", 4, "2600", 60)), catalogue(), NO_PEERS, NOW, 3);
        assertEquals(List.of(7, 8), ids(offers));
        assertEquals(1, offers.get(0).rank);
        assertTrue(offers.get(0).reason.contains("100% of your recent spend is on hotel"), offers.get(0).reason);
    }

    @Test
    void recentSpendCountsMoreThanOldSpend() {
        // 1000 on flights a year ago vs 400 on hotels last week: with a 90-day half-life hotels win
        List<Offer> offers = OfferModel.recommend(profile("BLUE", "LOW", null,
            bought(1, "FLIGHT", 1, "1000", 365), bought(7, "HOTEL", 1, "400", 7)), catalogue(), NO_PEERS, NOW, 1);
        assertEquals("HOTEL", offers.get(0).category);
    }

    @Test
    void peersSupplyAColdStartRecommendation() {
        Map<String, Map<Integer, Double>> peers = Map.of("GOLD", Map.of(16, 0.6, 19, 0.2), "BLUE", Map.of(11, 0.9));
        List<Offer> offers = OfferModel.recommend(profile("GOLD", "LOW", null), catalogue(), peers, NOW, 5);
        assertEquals(List.of(16, 19), ids(offers));               // GOLD peers only; no history, no trip
        assertTrue(offers.get(0).reason.contains("60% of GOLD members buy this"), offers.get(0).reason);
    }

    @Test
    void aCustomerWithNoHistoryAndNoPeersGetsNothing() {
        assertTrue(OfferModel.recommend(profile("BLUE", "LOW", null), catalogue(), NO_PEERS, NOW, 3).isEmpty());
    }

    @Test
    void consumablesAreRepurchased() {
        Purchase old = bought(11, "LOUNGE", 2, "440", 90);
        List<Offer> offers = OfferModel.recommend(profile("BLUE", "LOW", null, old), catalogue(), NO_PEERS, NOW, 3);
        assertEquals(11, offers.get(0).productId);
        assertTrue(offers.get(0).reason.contains("you last bought it 90 days ago"), offers.get(0).reason);
        // a hotel is not a consumable: no repurchase boost
        List<Offer> hotel = OfferModel.recommend(profile("BLUE", "LOW", null, bought(7, "HOTEL", 1, "650", 90)), catalogue(), NO_PEERS, NOW, 5);
        assertFalse(hotel.get(0).reason.contains("last bought"));
    }

    @Test
    void anUpcomingTripBoostsTripProducts() {
        Trip trip = new Trip("XA1103", "DXB", "LHR", NOW + 10 * DAY, false);
        List<Offer> offers = OfferModel.recommend(profile("GOLD", "LOW", trip), catalogue(), NO_PEERS, NOW, 5);
        assertEquals("HOTEL", offers.get(0).category);
        assertTrue(offers.stream().allMatch(o -> OfferModel.TRIP_CATEGORIES.contains(o.category)));
        assertTrue(offers.get(0).reason.contains("you fly DXB-LHR on 2026-"), offers.get(0).reason);
        assertTrue(offers.stream().noneMatch(o -> o.category.equals("FLIGHT")));
    }

    @Test
    void aTripBeyondTheWindowIsIgnored() {
        Trip far = new Trip("XA1", "DXB", "LHR", NOW + 46 * DAY, false);
        assertTrue(OfferModel.recommend(profile("GOLD", "LOW", far), catalogue(), NO_PEERS, NOW, 5).isEmpty());
    }

    @Test
    void aDisruptedFlightGetsALoungeGoodwillOfferWithExtraDiscount() {
        Trip delayed = new Trip("XA1103", "DXB", "LHR", NOW + 3 * DAY, true);
        List<Offer> offers = OfferModel.recommend(profile("GOLD", "LOW", delayed), catalogue(), NO_PEERS, NOW, 5);
        assertEquals(List.of(11), ids(offers));                       // only the lounge is boosted
        assertEquals(13, offers.get(0).discountPct);                  // 8 tier + 5 disruption
        assertTrue(offers.get(0).reason.contains("goodwill after the disruption to flight XA1103"), offers.get(0).reason);
        assertEquals(OfferModel.price(new BigDecimal("220"), 13), offers.get(0).offerPrice);
    }

    @Test
    void expensiveProductsAreDampedByPriceFit() {
        // average unit price 100: the 1200 flight (12x) keeps a sixth of its score, the 5800 flight (58x) falls below the cut-off
        Map<String, Map<Integer, Double>> peers = Map.of("BLUE", Map.of(1, 0.5, 2, 0.5));
        List<Offer> offers = OfferModel.recommend(profile("BLUE", "LOW", null, bought(19, "ANCILLARY", 10, "1000", 80)), catalogue(), peers, NOW, 8);
        assertTrue(ids(offers).contains(1));
        assertFalse(ids(offers).contains(2));
        Offer flight = offers.stream().filter(o -> o.productId == 1).findFirst().orElseThrow();
        assertEquals(0.8 * 0.5 * (2.0 / 12), flight.score, 1e-4);
    }

    @Test
    void atMostTwoOffersPerCategory() {
        List<Product> cat = new ArrayList<>();
        for (int i = 1; i <= 5; i++) cat.add(product(i, "HOTEL", "100"));
        cat.add(product(20, "LOUNGE", "100"));
        Map<String, Map<Integer, Double>> peers = Map.of("BLUE", Map.of(1, 0.9, 2, 0.8, 3, 0.7, 4, 0.6, 5, 0.5, 20, 0.1));
        List<Offer> offers = OfferModel.recommend(profile("BLUE", "LOW", null), cat, peers, NOW, 5);
        assertEquals(3, offers.size());
        assertEquals(2, offers.stream().filter(o -> o.category.equals("HOTEL")).count());
        assertEquals(List.of(1, 2, 20), ids(offers));
    }

    @Test
    void topNLimitsTheListAndRanksAreSequential() {
        Map<String, Map<Integer, Double>> peers = Map.of("BLUE", Map.of(7, 0.9, 11, 0.8, 13, 0.7, 16, 0.6, 19, 0.5));
        List<Offer> three = OfferModel.recommend(profile("BLUE", "LOW", null), catalogue(), peers, NOW, 3);
        assertEquals(3, three.size());
        assertEquals(List.of(1, 2, 3), three.stream().map(o -> o.rank).collect(Collectors.toList()));
        assertEquals(1, OfferModel.recommend(profile("BLUE", "LOW", null), catalogue(), peers, NOW, 1).size());
        for (int i = 1; i < three.size(); i++) assertTrue(three.get(i - 1).score >= three.get(i).score);
    }

    @Test
    void tiesBreakByProductId() {
        Map<String, Map<Integer, Double>> peers = Map.of("BLUE", Map.of(16, 0.5, 11, 0.5, 13, 0.5));
        assertEquals(List.of(11, 13, 16), ids(OfferModel.recommend(profile("BLUE", "LOW", null), catalogue(), peers, NOW, 3)));
    }

    @Test
    void offersCarryPricingValidityAndIdentity() {
        Map<String, Map<Integer, Double>> peers = Map.of("PLATINUM", Map.of(11, 0.5));
        Offer o = OfferModel.recommend(profile("PLATINUM", "HIGH", null), catalogue(), peers, NOW, 1).get(0);
        assertEquals(7, o.customerId);
        assertEquals(17, o.discountPct);
        assertEquals(new BigDecimal("220"), o.listPrice);
        assertEquals(new BigDecimal("182.60"), o.offerPrice);
        assertEquals(NOW, o.generatedMs);
        assertEquals(NOW + 14 * DAY, o.expiresMs);
        assertEquals("LOU-11", o.sku);
        assertEquals(0.4, o.score, 1e-9);                             // 0.8 * 0.5
    }

    @Test
    void scoresAreRoundedToFourDecimals() {
        Map<String, Map<Integer, Double>> peers = Map.of("BLUE", Map.of(11, 1.0 / 3));
        double s = OfferModel.recommend(profile("BLUE", "LOW", null), catalogue(), peers, NOW, 1).get(0).score;
        assertEquals(0.2667, s, 1e-12);
    }

    @Test
    void theModelIsDeterministic() {
        Map<String, Map<Integer, Double>> peers = new HashMap<>(Map.of("GOLD", Map.of(7, 0.4, 11, 0.4, 16, 0.4)));
        Profile p = profile("GOLD", "MEDIUM", new Trip("XA1", "DXB", "JFK", NOW + 5 * DAY, false), bought(7, "HOTEL", 2, "1300", 40), bought(11, "LOUNGE", 1, "220", 50));
        List<Offer> a = OfferModel.recommend(p, catalogue(), peers, NOW, 4), b = OfferModel.recommend(p, catalogue(), peers, NOW, 4);
        assertEquals(ids(a), ids(b));
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).score, b.get(i).score);
            assertEquals(a.get(i).reason, b.get(i).reason);
        }
    }
}
