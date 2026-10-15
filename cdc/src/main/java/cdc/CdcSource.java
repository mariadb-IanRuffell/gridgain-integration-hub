package cdc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** One replication source (Sybase or Oracle) feeding GridGain. The CLI and the stats publisher work against this. */
interface CdcSource extends AutoCloseable {
    String consumer();                                  // label used in cdc_status / the source's own offset table
    Connection gg();
    void connect() throws SQLException;                 // (re)connect after a failure
    void init() throws Exception;                       // create the GridGain tables, copy a snapshot, set the offset
    Result applyOnce(int limit) throws SQLException;    // apply pending changes; events == 0 means caught up
    long position() throws SQLException;                // reader position (Sybase change_id / Oracle SCN)
    long pending() throws SQLException;                 // committed changes not yet applied
    List<String> tableNames();
    long countRows(String table) throws SQLException;   // row count in the SOURCE system
    void status() throws SQLException;
    boolean verify() throws SQLException;
    @Override void close();
}
