package poc.api.repo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/** Reads a result row left to right (positional, so it does not depend on how the driver names columns). */
final class Cols {
    private final ResultSet rs;
    private int i;

    Cols(ResultSet rs) {
        this.rs = rs;
    }

    Integer integer() throws SQLException {
        int v = rs.getInt(++i);
        return rs.wasNull() ? null : v;
    }

    String string() throws SQLException {
        String v = rs.getString(++i);
        return v == null ? null : v.stripTrailing();
    }

    BigDecimal decimal() throws SQLException {
        return rs.getBigDecimal(++i);
    }

    Boolean bool() throws SQLException {
        boolean v = rs.getBoolean(++i);
        return rs.wasNull() ? null : v;
    }

    Instant instant() throws SQLException {
        Timestamp t = rs.getTimestamp(++i);
        return t == null ? null : t.toInstant();
    }

    LocalDate date() throws SQLException {
        java.sql.Date d = rs.getDate(++i);
        return d == null ? null : d.toLocalDate();
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
