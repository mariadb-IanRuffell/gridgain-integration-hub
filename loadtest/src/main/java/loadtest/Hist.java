package loadtest;

import java.util.concurrent.atomic.LongAdder;

/**
 * Latency histogram with logarithmic buckets (5% resolution from 1 microsecond to about 3 hours), cheap enough to record into from every worker.
 * Percentiles for a window are computed from the difference of two snapshots.
 */
final class Hist {
    static final int BUCKETS = 512;
    private static final double BASE = 1.05, LOG_BASE = Math.log(BASE);
    private final LongAdder[] counts = new LongAdder[BUCKETS];

    Hist() {
        for (int i = 0; i < BUCKETS; i++) counts[i] = new LongAdder();
    }

    static int bucket(long nanos) {
        double micros = Math.max(1, nanos / 1000.0);
        return Math.min(BUCKETS - 1, (int) (Math.log(micros) / LOG_BASE));
    }

    void record(long nanos) {
        counts[bucket(nanos)].increment();
    }

    long[] snapshot() {
        long[] s = new long[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) s[i] = counts[i].sum();
        return s;
    }

    static long[] minus(long[] a, long[] b) {
        long[] d = new long[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) d[i] = a[i] - (b == null ? 0 : b[i]);
        return d;
    }

    static long total(long[] c) {
        long n = 0;
        for (long v : c) n += v;
        return n;
    }

    /** Upper bound of the bucket holding the p-th percentile, in milliseconds; 0 when there are no samples. */
    static double percentileMs(long[] c, double p) {
        long n = total(c);
        if (n == 0) return 0;
        long target = (long) Math.ceil(n * p / 100.0), seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += c[i];
            if (seen >= target) return Math.pow(BASE, i + 1) / 1000.0;
        }
        return Math.pow(BASE, BUCKETS) / 1000.0;
    }
}
