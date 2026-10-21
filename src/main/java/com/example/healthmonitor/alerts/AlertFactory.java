package com.example.healthmonitor.alerts;

import com.example.healthmonitor.metrics.MetricsBuffer;
import com.example.healthmonitor.model.AlertKind;
import com.example.healthmonitor.model.HealthAlert;
import com.example.healthmonitor.model.MetricsSnapshot;
import com.example.healthmonitor.model.WindowStats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Builds {@link HealthAlert} events, including the statistics over the recent window. */
@ApplicationScoped
public class AlertFactory {

    @Inject MetricsBuffer buffer;

    @Inject
    @ConfigProperty(name = "alerts.window-seconds", defaultValue = "30")
    int windowSeconds;

    public int windowSeconds() {
        return windowSeconds;
    }

    public WindowStats currentWindow() {
        return stats(windowSeconds, buffer.window(Duration.ofSeconds(windowSeconds)));
    }

    /** Empty when no metrics have arrived yet, because there is nothing to analyse. */
    public Optional<HealthAlert> create(final AlertKind kind, final String detail) {
        final Optional<MetricsSnapshot> latest = buffer.latest();
        if (latest.isEmpty()) { return Optional.empty(); }
        return Optional.of(new HealthAlert(kind, detail, Instant.now(), currentWindow(), latest.get()));
    }

    static WindowStats stats(final int seconds, final List<MetricsSnapshot> window) {
        int n = window.size();
        if (n == 0) { return new WindowStats(seconds, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null); }
        double cpuSum       = 0;
        double cpuMax       = 0;
        double memSum       = 0;
        double memMax       = 0;
        double netSum       = 0;
        double netMax       = 0;
        double diskSum      = 0;
        double diskMax      = 0;
        Double batteryStart = null;
        Double batteryEnd   = null;
        for (MetricsSnapshot s : window) {
            cpuSum += s.cpu().percent();
            cpuMax = Math.max(cpuMax, s.cpu().percent());
            memSum += s.memory().percent();
            memMax = Math.max(memMax, s.memory().percent());
            netSum += s.network().percent();
            netMax = Math.max(netMax, s.network().percent());
            diskSum += s.disk().percent();
            diskMax = Math.max(diskMax, s.disk().percent());
            if (s.battery() != null) {
                if (batteryStart == null) {
                    batteryStart = s.battery().percent();
                }
                batteryEnd = s.battery().percent();
            }
        }
        return new WindowStats(seconds, n, cpuSum / n, cpuMax, memSum / n, memMax, netSum / n, netMax, diskSum / n, diskMax, batteryStart, batteryEnd);
    }
}
