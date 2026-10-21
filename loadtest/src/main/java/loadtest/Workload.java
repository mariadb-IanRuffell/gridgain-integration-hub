package loadtest;

import java.util.Random;

/** The OLTP operation mix and key selection. Pure functions of the profile and a random source, so they can be tested without a cluster. */
final class Workload {
    enum Op { READ, UPDATE, INSERT, RANGE, TX }

    private final int[] weights;
    private final int total;
    private final long rows;
    private final double skew;

    Workload(Profile p) {
        this(p.mix, p.rows, p.keySkew);
    }

    Workload(int[] weights, long rows, double skew) {
        this.weights = weights.clone();
        int t = 0;
        for (int w : weights) t += Math.max(0, w);
        this.total = t;
        this.rows = rows;
        this.skew = skew;
    }

    Op pick(Random rnd) {
        return pick(rnd.nextInt(total));
    }

    /** Operation for a roll in [0, sum of weights). */
    Op pick(int roll) {
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += Math.max(0, weights[i]);
            if (roll < acc) return Op.values()[i];
        }
        return Op.READ;
    }

    /** Existing row id in 1..rows; skew 1 is uniform, higher skews concentrate on low ids (hot keys). */
    long key(Random rnd) {
        long k = 1L + (long) (rows * Math.pow(rnd.nextDouble(), skew));
        return Math.min(rows, k);
    }

    /** Customer id for the indexed range query: about ten accounts each. */
    int customer(Random rnd) {
        return 1 + rnd.nextInt((int) Math.max(1, rows / 10));
    }

    /** Id for a row inserted by a worker: above the seeded range and unique per run, thread and sequence number. */
    long newId(long runSalt, int thread, long seq) {
        return rows + runSalt * 1_000_000_000L + thread * 1_000_000L + seq;
    }
}
