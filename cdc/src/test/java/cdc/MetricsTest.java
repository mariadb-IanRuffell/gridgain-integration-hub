package cdc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MetricsTest {
    CdcSource src;
    Connection gg;
    PreparedStatement merge, counts;

    @BeforeEach
    void setUp() throws Exception {
        src = mock(CdcSource.class);
        gg = mock(Connection.class);
        merge = mock(PreparedStatement.class);
        counts = mock(PreparedStatement.class);
        when(src.gg()).thenReturn(gg);
        when(src.consumer()).thenReturn("oracle");
        when(src.position()).thenReturn(777L);
        when(src.pending()).thenReturn(4L);
        when(src.tableNames()).thenReturn(List.of("flight", "booking"));
        when(src.countRows("flight")).thenReturn(238L);
        when(src.countRows("booking")).thenReturn(650L);
        when(gg.prepareStatement(startsWith("MERGE INTO cdc_status"))).thenReturn(merge);
        when(gg.prepareStatement(startsWith("MERGE INTO cdc_table_counts"))).thenReturn(counts);
    }

    private static String startsWith(String p) {
        return org.mockito.ArgumentMatchers.startsWith(p);
    }

    @Test
    void recordsNothingForEmptyBatches() throws Exception {
        Metrics m = new Metrics(src);
        m.record(new Result(0, 0, 0, 5, 0, 3));
        m.tick();          // heartbeat is due on the first tick
        ArgumentCaptor<Long> longs = ArgumentCaptor.forClass(Long.class);
        verify(merge, atLeastOnce()).setLong(anyInt(), longs.capture());
        // total_events (4th placeholder) stays 0
        verify(merge).setLong(4, 0L);
        verify(merge).setLong(7, 0L);
    }

    @Test
    void accumulatesCountersAndPublishesThem() throws Exception {
        Metrics m = new Metrics(src);
        long ago = System.currentTimeMillis() - 1500;
        m.record(new Result(10, 8, 2, 100, ago, 25));
        m.record(new Result(5, 5, 0, 105, ago, 12));
        m.tick();

        verify(merge).setString(1, "oracle");
        verify(merge).setLong(2, 777L);       // position
        verify(merge).setLong(3, 4L);         // pending
        verify(merge).setLong(4, 15L);        // total events
        verify(merge).setLong(5, 13L);        // upserts
        verify(merge).setLong(6, 2L);         // deletes
        verify(merge).setLong(7, 2L);         // batches
        verify(merge).setInt(8, 5);           // last batch events
        verify(merge).setLong(9, 12L);        // last batch ms
        ArgumentCaptor<Long> lat = ArgumentCaptor.forClass(Long.class);
        verify(merge).setLong(eq(10), lat.capture());
        assertTrue(lat.getValue() >= 1500 && lat.getValue() < 10_000, "latency " + lat.getValue());
        verify(merge).executeUpdate();
    }

    @Test
    void latencyIsZeroWhenTheBatchHasNoTimestamp() throws Exception {
        Metrics m = new Metrics(src);
        m.record(new Result(1, 1, 0, 1, 0, 1));
        m.tick();
        verify(merge).setLong(10, 0L);
    }

    @Test
    void publishesSourceRowCountsInOneBatch() throws Exception {
        new Metrics(src).tick();
        verify(counts).setString(1, "flight");
        verify(counts).setLong(2, 238L);
        verify(counts).setString(1, "booking");
        verify(counts).setLong(2, 650L);
        verify(counts, times(2)).addBatch();
        verify(counts).executeBatch();
    }

    @Test
    void secondTickWithinTheIntervalDoesNotWriteAgain() throws Exception {
        Metrics m = new Metrics(src);
        m.tick();
        m.record(new Result(1, 1, 0, 1, 0, 1));   // dirty, but the busy interval (1 s) has not elapsed
        m.tick();
        verify(merge, times(1)).executeUpdate();
        verify(counts, times(1)).executeBatch();
    }

    @Test
    void ensureTablesCreatesBothTablesAndRestoresCounters() throws Exception {
        Statement st = mock(Statement.class);
        when(gg.createStatement()).thenReturn(st);
        PreparedStatement sel = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(gg.prepareStatement(startsWith("SELECT total_events"))).thenReturn(sel);
        when(sel.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(100L);
        when(rs.getLong(2)).thenReturn(90L);
        when(rs.getLong(3)).thenReturn(10L);
        when(rs.getLong(4)).thenReturn(7L);

        Metrics m = new Metrics(src);
        m.ensureTables();
        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(st, times(2)).execute(ddl.capture());
        assertTrue(ddl.getAllValues().get(0).contains("cdc_status"));
        assertTrue(ddl.getAllValues().get(1).contains("cdc_table_counts"));
        verify(sel).setString(1, "oracle");

        m.record(new Result(1, 1, 0, 1, 0, 1));   // continues from the restored totals
        m.tick();
        verify(merge).setLong(4, 101L);
        verify(merge).setLong(5, 91L);
        verify(merge).setLong(6, 10L);
        verify(merge).setLong(7, 8L);
    }

    @Test
    void ensureTablesWithNoPreviousRowStartsAtZero() throws Exception {
        when(gg.createStatement()).thenReturn(mock(Statement.class));
        PreparedStatement sel = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(gg.prepareStatement(startsWith("SELECT total_events"))).thenReturn(sel);
        when(sel.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        Metrics m = new Metrics(src);
        m.ensureTables();
        m.record(new Result(3, 3, 0, 1, 0, 1));
        m.tick();
        verify(merge).setLong(4, 3L);
        verify(merge).setLong(7, 1L);
    }

    @Test
    void startedAtIsStableAcrossFlushes() throws Exception {
        Metrics m = new Metrics(src);
        m.tick();
        ArgumentCaptor<Timestamp> started = ArgumentCaptor.forClass(Timestamp.class);
        verify(merge).setTimestamp(eq(11), started.capture());
        assertTrue(Math.abs(started.getValue().getTime() - System.currentTimeMillis()) < 5_000);
    }
}
