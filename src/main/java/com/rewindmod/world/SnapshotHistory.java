package com.rewindmod.world;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Maintains a rolling history of WorldSnapshots for up to HISTORY_SECONDS seconds.
 * Snapshots are captured at ~20 tps (every tick).
 */
public class SnapshotHistory {

    public static final int HISTORY_SECONDS = 6; // keep 6s, rewind uses 5s
    public static final int TPS = 20;
    public static final int MAX_SNAPSHOTS = HISTORY_SECONDS * TPS; // 120 snapshots

    private final Deque<WorldSnapshot> snapshots = new ArrayDeque<>();

    /**
     * Add a new snapshot, dropping the oldest if over capacity.
     */
    public void add(WorldSnapshot snapshot) {
        snapshots.addLast(snapshot);
        while (snapshots.size() > MAX_SNAPSHOTS) {
            snapshots.pollFirst();
        }
    }

    /**
     * Returns the snapshot that is approximately {@code secondsAgo} seconds in the past,
     * or the oldest available snapshot if history is shorter.
     */
    public WorldSnapshot getSnapshotSecondsAgo(int secondsAgo) {
        if (snapshots.isEmpty()) return null;

        long targetTime = System.currentTimeMillis() - (secondsAgo * 1000L);
        WorldSnapshot best = snapshots.peekFirst(); // oldest

        for (WorldSnapshot snap : snapshots) {
            if (snap.getTimestamp() <= targetTime) {
                best = snap; // keep advancing while we're still before or at target
            } else {
                break;
            }
        }
        return best;
    }

    public boolean isEmpty() {
        return snapshots.isEmpty();
    }

    public int size() {
        return snapshots.size();
    }

    public void clear() {
        snapshots.clear();
    }
}
