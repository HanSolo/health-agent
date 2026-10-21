package com.example.healthmonitor.model;

import java.time.Instant;

/**
 * The CDI event that triggers the agent workflow.
 *
 * @param kind       what was detected (or MANUAL)
 * @param detail     one human-readable sentence, e.g. the threshold that was crossed
 * @param detectedAt when the alert was raised
 * @param window     statistics over the recent snapshots
 * @param latest     the newest snapshot, including top processes when available
 */
public record HealthAlert(AlertKind kind, String detail, Instant detectedAt, WindowStats window, MetricsSnapshot latest) {
    public boolean manual() {
        return kind == AlertKind.MANUAL;
    }
}
