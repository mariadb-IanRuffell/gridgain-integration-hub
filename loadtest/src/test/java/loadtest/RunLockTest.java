package loadtest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLockTest {
    @Test
    void aFreeLockCanBeTakenAndIsReleased(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("RUNNING");
        assertEquals(-1, RunLock.holder(f));
        try (RunLock l = RunLock.acquire(f)) {
            assertEquals(ProcessHandle.current().pid(), RunLock.holder(f));
        }
        assertFalse(Files.exists(f));
    }

    @Test
    void aLockHeldByALiveProcessBlocksTheNextRun(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("RUNNING");
        Process other = new ProcessBuilder("sleep", "30").start();
        try {
            Files.writeString(f, Long.toString(other.pid()));
            assertEquals(other.pid(), RunLock.holder(f));
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> RunLock.acquire(f));
            assertTrue(e.getMessage().contains("another load test is running"));
            assertTrue(assertThrows(IllegalStateException.class, () -> RunLock.requireFree(f, "clean up")).getMessage().contains("cannot clean up"));
            assertTrue(Files.exists(f));          // the failed attempt must not remove the other run's lock
        } finally {
            other.destroyForcibly();
        }
    }

    @Test
    void aStaleLockFromACrashedRunIsIgnored(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("RUNNING");
        Process p = new ProcessBuilder("true").start();
        p.waitFor();
        Files.writeString(f, Long.toString(p.pid()));
        assertEquals(-1, RunLock.holder(f));
        RunLock.requireFree(f, "prepare");        // does not throw
        try (RunLock l = RunLock.acquire(f)) {
            assertEquals(ProcessHandle.current().pid(), RunLock.holder(f));
        }
    }

    @Test
    void garbageInTheLockFileCountsAsFree(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("RUNNING");
        Files.writeString(f, "not a pid");
        assertEquals(-1, RunLock.holder(f));
    }
}
