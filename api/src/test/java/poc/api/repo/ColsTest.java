package poc.api.repo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ColsTest {
    @Test
    void readsColumnsLeftToRight() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt(1)).thenReturn(42);
        when(rs.getString(2)).thenReturn("GOLD   ");
        when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("1.50"));
        when(rs.getBoolean(4)).thenReturn(true);
        when(rs.getTimestamp(5)).thenReturn(Timestamp.from(Instant.parse("2026-10-15T12:00:00Z")));
        when(rs.getDate(6)).thenReturn(Date.valueOf("2026-10-15"));
        when(rs.wasNull()).thenReturn(false);
        Cols c = new Cols(rs);
        assertEquals(42, c.integer());
        assertEquals("GOLD", c.string());                           // CHAR padding is stripped
        assertEquals(new BigDecimal("1.50"), c.decimal());
        assertEquals(true, c.bool());
        assertEquals(Instant.parse("2026-10-15T12:00:00Z"), c.instant());
        assertEquals(LocalDate.of(2026, 10, 15), c.date());
    }

    @Test
    void sqlNullsBecomeJavaNulls() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.wasNull()).thenReturn(true);                        // int 0 / boolean false that were really NULL
        when(rs.getString(3)).thenReturn(null);
        when(rs.getTimestamp(4)).thenReturn(null);
        when(rs.getDate(5)).thenReturn(null);
        Cols c = new Cols(rs);
        assertNull(c.integer());
        assertNull(c.bool());
        assertNull(c.string());
        assertNull(c.instant());
        assertNull(c.date());
    }

    @Test
    void doublesKeepNullness() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getDouble(1)).thenReturn(1.25);
        when(rs.getDouble(2)).thenReturn(0.0);
        when(rs.wasNull()).thenReturn(false, true);
        Cols c = new Cols(rs);
        assertEquals(1.25, c.dbl());
        assertNull(c.dbl());
    }

    @Test
    void stripsOnlyTrailingWhitespace() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("  a b  ");
        assertEquals("  a b", new Cols(rs).string());
    }

    @Test
    void instantToTimestamp() {
        assertNull(Cols.ts(null));
        Instant i = Instant.parse("2026-10-15T12:00:00.123Z");
        assertEquals(i, Cols.ts(i).toInstant());
    }
}
