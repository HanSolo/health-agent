package com.example.healthmonitor.metrics;

import com.example.healthmonitor.model.MetricsSnapshot;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Rolling in-memory history of the most recent snapshots.
 *
 * <p>One writer (the WebSocket client thread) and many readers, so a concurrent deque is
 * enough. Time is measured with the snapshots' own timestamps, which come from the
 * same Mac, so no clock comparison with this JVM is needed.
 */
@ApplicationScoped
public class MetricsBuffer {
    private static final Duration                               RETENTION = Duration.ofMinutes(5);
    private        final ConcurrentLinkedDeque<MetricsSnapshot> snapshots = new ConcurrentLinkedDeque<>();

    public void add(final MetricsSnapshot snapshot) {
        if (snapshot == null || snapshot.timestamp() == null) { return; }

        snapshots.addLast(snapshot);
        Instant         cutoff = snapshot.timestamp().minus(RETENTION);
        MetricsSnapshot oldest;
        while ((oldest = snapshots.peekFirst()) != null && (oldest.timestamp() == null || oldest.timestamp().isBefore(cutoff))) {
            snapshots.pollFirst();
        }
    }

    public Optional<MetricsSnapshot> latest() {
        return Optional.ofNullable(snapshots.peekLast());
    }

    /** Snapshots from the last {@code duration}, measured back from the newest one. Oldest first. */
    public List<MetricsSnapshot> window(final Duration duration) {
        MetricsSnapshot newest = snapshots.peekLast();
        if (newest == null) { return List.of(); }

        Instant from = newest.timestamp().minus(duration);
        List<MetricsSnapshot> result = new ArrayList<>();
        for (MetricsSnapshot snapshot : snapshots) {
            if (snapshot.timestamp() != null && !snapshot.timestamp().isBefore(from)) {
                result.add(snapshot);
            }
        }
        return result;
    }

    public int size() {
        return snapshots.size();
    }
}
