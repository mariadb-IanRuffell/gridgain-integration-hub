package loadtest;

/**
 * The safety valve. After every measurement window it looks at latency and errors and keeps a ceiling on the number of threads:
 * <ul>
 *   <li>unhealthy windows (p95 above the SLO or errors above the SLO) in a row: lower the ceiling to 70% of the current load (back off);</li>
 *   <li>healthy windows in a row: raise the ceiling again, a tenth of the peak at a time (recover);</li>
 *   <li>errors far above the SLO for several windows, even at the lowest load: abort, so a struggling cluster is not pushed further.</li>
 * </ul>
 * The thread count actually used is min(planned shape, ceiling).
 */
final class Governor {
    enum Action { NONE, BACKOFF, RECOVER, ABORT }

    record Decision(Action action, int ceiling, String reason) {}

    private final int min, peak, breachWindows, recoverWindows, abortWindows;
    private final double sloP95Ms, sloErrorPct, abortErrorPct;
    private int ceiling, breachStreak, healthyStreak, abortStreak;

    Governor(int min, int peak, double sloP95Ms, double sloErrorPct, double abortErrorPct, int breachWindows, int recoverWindows, int abortWindows) {
        this.min = Math.max(1, min); this.peak = peak; this.sloP95Ms = sloP95Ms; this.sloErrorPct = sloErrorPct; this.abortErrorPct = abortErrorPct;
        this.breachWindows = breachWindows; this.recoverWindows = recoverWindows; this.abortWindows = abortWindows;
        this.ceiling = peak;
    }

    int ceiling() { return ceiling; }

    /** @param threads the number of threads that were running during the window */
    Decision observe(long ops, long errors, double p95Ms, int threads) {
        if (ops + errors == 0) return new Decision(Action.NONE, ceiling, "idle window");
        double errPct = 100.0 * errors / (ops + errors);

        abortStreak = errPct >= abortErrorPct ? abortStreak + 1 : 0;
        if (abortStreak >= abortWindows)
            return new Decision(Action.ABORT, ceiling, String.format("error rate %.1f%% for %d windows", errPct, abortStreak));

        boolean unhealthy = p95Ms > sloP95Ms || errPct > sloErrorPct;
        if (unhealthy) {
            healthyStreak = 0;
            if (++breachStreak >= breachWindows) {
                breachStreak = 0;
                int lowered = Math.max(min, (int) Math.floor(threads * 0.7));
                if (lowered < ceiling) {
                    ceiling = lowered;
                    return new Decision(Action.BACKOFF, ceiling, String.format("p95 %.0f ms, errors %.1f%% at %d threads", p95Ms, errPct, threads));
                }
            }
            return new Decision(Action.NONE, ceiling, "unhealthy");
        }
        breachStreak = 0;
        if (ceiling < peak && ++healthyStreak >= recoverWindows) {
            healthyStreak = 0;
            ceiling = Math.min(peak, ceiling + Math.max(1, peak / 10));
            return new Decision(Action.RECOVER, ceiling, "healthy");
        }
        return new Decision(Action.NONE, ceiling, "healthy");
    }
}
