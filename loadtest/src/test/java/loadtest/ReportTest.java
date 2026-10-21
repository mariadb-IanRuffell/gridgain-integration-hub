package loadtest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportTest {
    static Runner.Result result() {
        Runner.Result r = new Runner.Result();
        r.windows.add(new Window(5, 4, 20, 4, 1000, 0, 200, 0, 0, 1.5, 3.2, 5.0, new long[] {600, 150, 50, 100, 100}));
        r.windows.add(new Window(10, 8, 14, 8, 1800, 20, 360, 4, 1.1, 2.5, 280.0, 400.0, new long[] {1000, 300, 100, 200, 200}));
        r.events.add(new Runner.Event(10, "backed off to ceiling 14 (p95 280 ms) <script>"));
        r.totalOps = 2800; r.totalErrors = 20; r.bestOpsPerSec = 360; r.peakThreadsReached = 8; r.backoffs = 1;
        r.topErrors = Map.of("READ: timeout \"x\"", 20L);
        return r;
    }

    @Test
    void csvHasAHeaderAndOneRowPerWindow() {
        String[] lines = Report.csv(result().windows).split("\n");
        assertEquals(3, lines.length);
        assertEquals("t_sec,planned_threads,ceiling,threads,ops_per_sec,errors_per_sec,error_pct,p50_ms,p95_ms,p99_ms,read,update,insert,range,tx", lines[0]);
        assertEquals("5.0,4,20,4,200.0,0.00,0.00,1.50,3.20,5.00,600,150,50,100,100", lines[1]);
    }

    @Test
    void summaryDescribesTheOutcome() {
        Profile p = ProfileTest.of("peak_threads", "8");
        Runner.Result r = result();
        String s = Report.summaryJson(p, r, "2026-10-21 10:00:00", 10);
        assertTrue(s.contains("\"outcome\": \"completed\""));
        assertTrue(s.contains("\"backoffs\": 1"));
        assertTrue(s.contains("\"errorPct\": 0.71"));
        r.abortReason = "server node lost: 2 of 3 online";
        String aborted = Report.summaryJson(p, r, "x", 10);
        assertTrue(aborted.contains("\"outcome\": \"aborted\""));
        assertTrue(aborted.contains("server node lost"));
        r.abortReason = null; r.stoppedByUser = true;
        assertTrue(Report.summaryJson(p, r, "x", 10).contains("\"outcome\": \"stopped\""));
    }

    @Test
    void jsonStringsAreEscapedAndSafeInsideAScriptTag() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", Report.json("a\"b\\c\nd"));
        assertFalse(Report.json("</script>").contains("<"));
    }

    @Test
    void htmlEmbedsTheData() throws IOException {
        String html = Report.html(ProfileTest.of(), result(), "2026-10-21 10:00:00", 10);
        assertFalse(html.contains("__DATA__"));
        assertTrue(html.contains("\"profile\":\"t\""));
        assertTrue(html.contains("[10.0,8,14,8,360.0,4.00,1.10,2.50,280.00,400.00]"));
        assertFalse(html.contains("<script>\""));          // the event text containing <script> is escaped
        assertTrue(html.contains("\\u003cscript>"));
    }

    @Test
    void writeCreatesAllThreeFiles(@TempDir Path dir) throws IOException {
        Report.write(dir.resolve("run1"), ProfileTest.of(), result(), "now", 10);
        for (String f : List.of("timeseries.csv", "summary.json", "report.html")) assertTrue(Files.size(dir.resolve("run1").resolve(f)) > 100, f);
    }
}
