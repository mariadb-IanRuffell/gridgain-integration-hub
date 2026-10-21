package loadtest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import java.util.StringJoiner;

/** Writes a run's results: timeseries.csv, summary.json and a self-contained report.html (canvas charts, no external libraries). */
final class Report {
    private Report() {}

    static String csv(List<Window> ws) {
        StringBuilder b = new StringBuilder("t_sec,planned_threads,ceiling,threads,ops_per_sec,errors_per_sec,error_pct,p50_ms,p95_ms,p99_ms");
        for (String o : Profile.OPS) b.append(',').append(o);
        b.append('\n');
        for (Window w : ws) {
            b.append(String.format(Locale.ROOT, "%.1f,%d,%d,%d,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f", w.t(), w.planned(), w.ceiling(), w.threads(), w.opsPerSec(), w.errorsPerSec(),
                w.errorPct(), w.p50Ms(), w.p95Ms(), w.p99Ms()));
            for (long v : w.byOp()) b.append(',').append(v);
            b.append('\n');
        }
        return b.toString();
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '<' -> b.append("\\u003c");      // keeps the data safe inside a script tag
                default -> b.append(c < 32 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return b.append('"').toString();
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static String summaryJson(Profile p, Runner.Result r, String started, double seconds) {
        StringJoiner j = new StringJoiner(",\n  ", "{\n  ", "\n}\n");
        j.add("\"profile\": " + json(p.name));
        j.add("\"started\": " + json(started));
        j.add("\"seconds\": " + num(seconds));
        j.add("\"outcome\": " + json(r.abortReason != null ? "aborted" : r.stoppedByUser ? "stopped" : "completed"));
        j.add("\"abortReason\": " + (r.abortReason == null ? "null" : json(r.abortReason)));
        j.add("\"peakThreadsPlanned\": " + p.peakThreads);
        j.add("\"peakThreadsReached\": " + r.peakThreadsReached);
        j.add("\"maxThreadsCap\": " + p.maxThreads);
        j.add("\"backoffs\": " + r.backoffs);
        j.add("\"totalOps\": " + r.totalOps);
        j.add("\"totalErrors\": " + r.totalErrors);
        j.add("\"errorPct\": " + num(r.totalOps + r.totalErrors == 0 ? 0 : 100.0 * r.totalErrors / (r.totalOps + r.totalErrors)));
        j.add("\"bestOpsPerSec\": " + num(r.bestOpsPerSec));
        j.add("\"rowsInsertedAndRemoved\": " + r.insertedRows);
        j.add("\"sloP95Ms\": " + num(p.sloP95Ms));
        j.add("\"sloErrorPct\": " + num(p.sloErrorPct));
        StringJoiner errs = new StringJoiner(", ", "{", "}");
        r.topErrors.forEach((k, v) -> errs.add(json(k) + ": " + v));
        j.add("\"topErrors\": " + errs);
        return j.toString();
    }

    static String dataJson(Profile p, Runner.Result r, String started, double seconds) {
        StringBuilder b = new StringBuilder("{\"profile\":").append(json(p.name)).append(",\"started\":").append(json(started)).append(",\"slo\":{\"p95\":")
            .append(num(p.sloP95Ms)).append(",\"err\":").append(num(p.sloErrorPct)).append("},\"ops\":[");
        for (int i = 0; i < Profile.OPS.length; i++) b.append(i > 0 ? "," : "").append(json(Profile.OPS[i]));
        b.append("],\"rows\":[");
        for (int i = 0; i < r.windows.size(); i++) {
            Window w = r.windows.get(i);
            b.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "[%.1f,%d,%d,%d,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f]", w.t(), w.planned(), w.ceiling(), w.threads(), w.opsPerSec(),
                w.errorsPerSec(), w.errorPct(), w.p50Ms(), w.p95Ms(), w.p99Ms()));
        }
        b.append("],\"events\":[");
        for (int i = 0; i < r.events.size(); i++) b.append(i > 0 ? "," : "").append("[").append(num(r.events.get(i).t())).append(",").append(json(r.events.get(i).text())).append("]");
        b.append("],\"summary\":").append(summaryJson(p, r, started, seconds).trim()).append("}");
        return b.toString();
    }

    static String html(Profile p, Runner.Result r, String started, double seconds) throws IOException {
        try (InputStream in = Report.class.getResourceAsStream("/report.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("__DATA__", dataJson(p, r, started, seconds));
        }
    }

    static void write(Path dir, Profile p, Runner.Result r, String started, double seconds) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("timeseries.csv"), csv(r.windows));
        Files.writeString(dir.resolve("summary.json"), summaryJson(p, r, started, seconds));
        Files.writeString(dir.resolve("report.html"), html(p, r, started, seconds));
    }

}
