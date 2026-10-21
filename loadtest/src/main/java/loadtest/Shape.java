package loadtest;

/** The planned load: threads ramp from min to peak, hold, ramp back down; repeated for the number of cycles; then zero. */
final class Shape {
    private final int min, peak, up, hold, down, cycles;

    Shape(int min, int peak, int upSec, int holdSec, int downSec, int cycles) {
        this.min = min; this.peak = peak; this.up = upSec; this.hold = holdSec; this.down = downSec; this.cycles = cycles;
    }

    double cycleSec() { return up + hold + down; }

    double totalSec() { return cycleSec() * cycles; }

    /** Planned number of active threads t seconds into the run. */
    int threadsAt(double t) {
        if (t < 0 || t >= totalSec()) return 0;
        double c = t % cycleSec();
        if (c < up) return (int) Math.round(min + (peak - min) * (c / up));
        if (c < up + hold) return peak;
        return (int) Math.round(peak - (peak - min) * ((c - up - hold) / down));
    }

    /** Which cycle (1-based) and phase (up / hold / down) t falls in, for the live status line. */
    String phaseAt(double t) {
        if (t >= totalSec()) return "done";
        double c = t % cycleSec();
        String ph = c < up ? "up" : c < up + hold ? "hold" : "down";
        return "cycle " + ((int) (t / cycleSec()) + 1) + "/" + cycles + " " + ph;
    }
}
