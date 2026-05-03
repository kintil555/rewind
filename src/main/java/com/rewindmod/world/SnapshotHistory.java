package com.rewindmod.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Rolling history of WorldSnapshots captured at ~20 TPS.
 * Extended to support returning a list of the last N seconds for frame-by-frame playback.
 */
public class SnapshotHistory {

    public static final int HISTORY_SECONDS = 16;  // store 16s to allow up to 15s rewind
    public static final int TPS = 20;
    public static final int MAX_SNAPSHOTS = HISTORY_SECONDS * TPS; // 320 snapshots

    private final Deque<WorldSnapshot> snapshots = new ArrayDeque<>();

    public void add(WorldSnapshot snapshot) {
        snapshots.addLast(snapshot);
        while (snapshots.size() > MAX_SNAPSHOTS) {
            snapshots.pollFirst();
        }
    }

    /**
     * Returns all snapshots from the last {@code secondsAgo} seconds,
     * ordered oldest → newest. Used for frame-by-frame playback.
     */
    public List<WorldSnapshot> getLastSeconds(int secondsAgo) {
        if (snapshots.isEmpty()) return new ArrayList<>();

        long cutoff = System.currentTimeMillis() - (secondsAgo * 1000L);
        List<WorldSnapshot> result = new ArrayList<>();

        for (WorldSnapshot snap : snapshots) {
            if (snap.getTimestamp() >= cutoff) {
                result.add(snap);
            }
        }

        // If nothing qualifies, return the entire history as a fallback
        if (result.isEmpty()) {
            result.addAll(snapshots);
        }

        return result; // oldest → newest
    }

    /**
     * Returns the snapshot approximately {@code secondsAgo} seconds in the past.
     */
    public WorldSnapshot getSnapshotSecondsAgo(int secondsAgo) {
        if (snapshots.isEmpty()) return null;
        long targetTime = System.currentTimeMillis() - (secondsAgo * 1000L);
        WorldSnapshot best = snapshots.peekFirst();
        for (WorldSnapshot snap : snapshots) {
            if (snap.getTimestamp() <= targetTime) best = snap;
            else break;
        }
        return best;
    }

    public boolean isEmpty() { return snapshots.isEmpty(); }
    public int size() { return snapshots.size(); }
    public void clear() { snapshots.clear(); }
}
