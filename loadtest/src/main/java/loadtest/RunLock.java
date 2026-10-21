package loadtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One test at a time: a RUNNING file holding the owner's process id. Two runs would share the table (and each other's inserted rows), and
 * `cleanup` or `prepare` while a run is active would pull the table out from under it. A lock left behind by a crashed process is ignored.
 */
final class RunLock implements AutoCloseable {
    private final Path file;
    private final boolean owned;

    private RunLock(Path file, boolean owned) {
        this.file = file;
        this.owned = owned;
    }

    /** Process id holding the lock, or -1 when it is free (no file, unreadable, or the owner is gone). */
    static long holder(Path file) {
        try {
            if (!Files.exists(file)) return -1;
            long pid = Long.parseLong(Files.readString(file).trim());
            return ProcessHandle.of(pid).map(ph -> ph.isAlive() ? pid : -1L).orElse(-1L);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    static RunLock acquire(Path file) throws IOException {
        long h = holder(file);
        if (h != -1 && h != ProcessHandle.current().pid())
            throw new IllegalStateException("another load test is running (process " + h + "); `./run.sh stop` ends it. Lock file: " + file.toAbsolutePath());
        Files.writeString(file, Long.toString(ProcessHandle.current().pid()), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return new RunLock(file, true);
    }

    /** Refuse an action (cleanup, prepare) that would disturb a run in progress. */
    static void requireFree(Path file, String action) {
        long h = holder(file);
        if (h != -1) throw new IllegalStateException("cannot " + action + " while a load test is running (process " + h + "); stop it first with `./run.sh stop`");
    }

    @Override
    public void close() throws IOException {
        if (owned) Files.deleteIfExists(file);
    }
}
