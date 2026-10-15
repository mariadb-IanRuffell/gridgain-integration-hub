package cdc;

/** What one applyOnce() call did. oldestChangeMs is the timestamp of the oldest change in the batch (0 if none). */
record Result(int events, int upserts, int deletes, long lastChangeId, long oldestChangeMs, long durationMs) {}
