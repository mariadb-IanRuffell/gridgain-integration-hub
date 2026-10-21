package loadtest;

/** What happened in one measurement window, and what the load was set to for it. */
record Window(double t, int planned, int ceiling, int threads, long ops, long errors, double opsPerSec, double errorsPerSec, double errorPct,
              double p50Ms, double p95Ms, double p99Ms, long[] byOp) {}
