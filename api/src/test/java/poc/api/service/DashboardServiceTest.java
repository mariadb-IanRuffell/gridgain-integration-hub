package poc.api.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import poc.api.metrics.RequestMetrics;
import poc.api.repo.DashboardRepository;
import poc.api.repo.DashboardRepository.CdcRow;
import poc.api.repo.DashboardRepository.SourceCount;
import poc.api.service.DashboardService.*;

class DashboardServiceTest {
    RequestMetrics metrics = new RequestMetrics();
    DashboardRepository repo = mock(DashboardRepository.class);
    DashboardService service;

    static CdcRow row(long events, long upserts, long deletes, long pending, Instant updated) {
        return new CdcRow(77, pending, events, upserts, deletes, 3, 10, 20, 150, Instant.parse("2026-10-15T10:00:00Z"), updated);
    }

    @BeforeEach
    void setUp() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        DashboardRepository.TABLES.forEach(t -> sizes.put(t.name(), 10L));
        when(repo.gridGainRows()).thenReturn(sizes);
        when(repo.sourceRows()).thenReturn(Map.of());
        when(repo.serverNodes()).thenReturn(List.of("node1", "node2", "node3"));
        when(repo.cdcStatus(anyString())).thenReturn(Optional.empty());
        service = new DashboardService(metrics, repo);
    }

    @Test
    void summaryBeforeTheFirstSampleIsEmptyButNeverNull() {
        Summary s = service.summary();
        assertNotNull(s);
        assertTrue(s.tables().isEmpty());
        assertNull(s.totalSourceRows());
        assertEquals(0, s.cluster().nodes());
    }

    @Test
    void firstSampleBuildsTheSummaryButNoSeriesPoint() {
        service.sample();
        Summary s = service.summary();
        assertEquals(17, s.tables().size());
        assertEquals(170, s.totalGridgainRows());
        assertEquals(3, s.cluster().nodes());
        assertEquals(List.of("node1", "node2", "node3"), s.cluster().nodeIds());
        assertTrue(service.series(0).samples().isEmpty());      // rates need two snapshots
        assertEquals(DashboardService.INTERVAL_MS, service.series(0).intervalMs());
    }

    @Test
    void tablesCarryTheirSourceAndTheDifferenceAgainstTheReaderCount() {
        Instant counted = Instant.parse("2026-10-15T12:00:00Z");
        when(repo.sourceRows()).thenReturn(Map.of("customer", new SourceCount(10, counted), "booking", new SourceCount(9, counted.plusSeconds(1))));
        service.sample();
        Summary s = service.summary();
        TableSize customer = s.tables().stream().filter(t -> t.name().equals("customer")).findFirst().orElseThrow();
        TableSize booking = s.tables().stream().filter(t -> t.name().equals("booking")).findFirst().orElseThrow();
        TableSize flight = s.tables().stream().filter(t -> t.name().equals("flight")).findFirst().orElseThrow();
        assertEquals("sybase", customer.source());
        assertEquals(0L, customer.difference());
        assertEquals("oracle", booking.source());
        assertEquals(1L, booking.difference());               // GridGain has one row the source does not (e.g. created through the API)
        assertNull(flight.sourceRows());
        assertNull(flight.difference());
        assertEquals(counted.plusSeconds(1), s.sourceCountsAt());
        assertNull(s.totalSourceRows());                      // not every table has a source count yet
    }

    @Test
    void totalSourceRowsIsReportedOnceEveryTableHasACount() {
        Map<String, SourceCount> all = new LinkedHashMap<>();
        DashboardRepository.TABLES.forEach(t -> all.put(t.name(), new SourceCount(10, Instant.now())));
        when(repo.sourceRows()).thenReturn(all);
        service.sample();
        assertEquals(170L, service.summary().totalSourceRows());
    }

    @Test
    void missingCdcCountsDoNotBreakTheSummary() {
        when(repo.sourceRows()).thenThrow(new RuntimeException("cdc_table_counts does not exist"));
        service.sample();
        assertEquals(17, service.summary().tables().size());
    }

    @Test
    void unreadableGridGainLeavesTheServiceRunning() {
        when(repo.gridGainRows()).thenThrow(new RuntimeException("cluster down"));
        service.sample();
        assertNotNull(service.summary());
    }

    @Test
    void restRatesAreDerivedFromCounterDeltas() throws Exception {
        service.sample();
        for (int i = 0; i < 10; i++) metrics.record("GET", 200, 4_000_000);   // avg 4 ms
        metrics.record("POST", 500, 4_000_000);
        Thread.sleep(50);
        service.sample();
        List<Sample> samples = service.series(0).samples();
        assertEquals(1, samples.size());
        Sample s = samples.get(0);
        assertTrue(s.apiReqPerSec() > 0);
        assertEquals(s.apiReqPerSec() * 10 / 11, s.apiReadsPerSec(), s.apiReqPerSec() * 0.01);
        assertTrue(s.apiWritesPerSec() > 0);
        assertTrue(s.apiErrorsPerSec() > 0);
        assertEquals(4.0, s.apiAvgMs(), 0.01);
    }

    @Test
    void idleIntervalsReportZeroAverageLatency() throws Exception {
        service.sample();
        Thread.sleep(10);
        service.sample();
        Sample s = service.series(0).samples().get(0);
        assertEquals(0, s.apiReqPerSec());
        assertEquals(0, s.apiAvgMs());
    }

    @Test
    void cdcRatesUseTheReadersOwnPublishTimestamps() throws Exception {
        long now = System.currentTimeMillis();
        when(repo.cdcStatus(DashboardRepository.SYBASE_CONSUMER)).thenReturn(
            Optional.of(row(1000, 900, 100, 4, Instant.ofEpochMilli(now - 2000))),
            Optional.of(row(1300, 1100, 200, 7, Instant.ofEpochMilli(now - 1000))));
        service.sample();
        Thread.sleep(5);
        service.sample();
        Sample s = service.series(0).samples().get(0);
        assertEquals(300.0, s.cdcEventsPerSec(), 1.0);       // 300 events over the reader's 1 s between publishes
        assertEquals(200.0, s.cdcUpsertsPerSec(), 1.0);
        assertEquals(100.0, s.cdcDeletesPerSec(), 1.0);
        assertEquals(7, s.cdcPending());
        assertEquals(150, s.cdcLatencyMs());
        assertEquals(0, s.oraEventsPerSec());
    }

    @Test
    void aStoppedReaderReadsAsZeroAndNotRunning() throws Exception {
        Instant stale = Instant.now().minusSeconds(60);
        when(repo.cdcStatus(DashboardRepository.ORACLE_CONSUMER)).thenReturn(Optional.of(row(5000, 5000, 0, 0, stale)));
        service.sample();
        Thread.sleep(5);
        service.sample();
        assertEquals(0, service.series(0).samples().get(0).oraEventsPerSec());
        CdcStatus status = service.summary().cdc().get(0);
        assertEquals("oracle", status.source());
        assertFalse(status.running());
        assertEquals(5000, status.totalEvents());
    }

    @Test
    void aLiveReaderIsRunningAndBothReadersAreListed() {
        Instant fresh = Instant.now();
        when(repo.cdcStatus(DashboardRepository.SYBASE_CONSUMER)).thenReturn(Optional.of(row(10, 10, 0, 0, fresh)));
        when(repo.cdcStatus(DashboardRepository.ORACLE_CONSUMER)).thenReturn(Optional.of(row(20, 20, 0, 0, fresh)));
        service.sample();
        List<CdcStatus> cdc = service.summary().cdc();
        assertEquals(List.of("sybase", "oracle"), cdc.stream().map(CdcStatus::source).toList());
        assertTrue(cdc.stream().allMatch(CdcStatus::running));
    }

    @Test
    void seriesCanBePolledIncrementally() throws Exception {
        service.sample();
        for (int i = 0; i < 3; i++) { Thread.sleep(5); service.sample(); }
        List<Sample> all = service.series(0).samples();
        assertEquals(3, all.size());
        assertEquals(1, service.series(all.get(1).t()).samples().size());
        assertTrue(service.series(all.get(2).t()).samples().isEmpty());
    }

    @Test
    void seriesKeepsAtMostFifteenMinutes() throws Exception {
        service.sample();
        for (int i = 0; i < DashboardService.KEEP + 20; i++) service.sample();
        assertEquals(DashboardService.KEEP, service.series(0).samples().size());
    }
}
