package org.indexact.session;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.indexact.index.Snapshot;

/** Thread-safe registry of immutable served snapshots. */
public final class SnapshotManager {
    private final Map<String, Snapshot> snapshots = new HashMap<>();

    public SnapshotManager() {}

    public SnapshotManager(Collection<Snapshot> snapshots) {
        snapshots.forEach(this::register);
    }

    public synchronized void register(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Snapshot existing = snapshots.putIfAbsent(snapshot.snapshotId(), snapshot);
        if (existing != null && existing != snapshot) {
            throw new IllegalArgumentException("a different snapshot is registered under this ID");
        }
    }

    public synchronized Snapshot get(String snapshotId) {
        return snapshots.get(snapshotId);
    }
}
