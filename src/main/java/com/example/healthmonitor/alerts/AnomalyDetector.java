package com.example.healthmonitor.alerts;

import com.example.healthmonitor.model.AlertKind;
import com.example.healthmonitor.model.HealthAlert;
import com.example.healthmonitor.model.MetricsSnapshot;
import com.example.healthmonitor.model.WindowStats;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Cheap threshold rules that decide <em>when</em> the agent is worth waking up.
 * The LLM decides what the situation means; it never sees the raw 1 Hz stream.
 *
 * <p>Runs on the WebSocket thread for every snapshot, so it only does arithmetic and hands
 * the alert to {@link AlertDispatcher}, which does the slow part on another thread.
 */
@ApplicationScoped
public class AnomalyDetector {
    private static final Logger LOG = Logger.getLogger(AnomalyDetector.class.getName());

    @Inject AlertFactory    factory;
    @Inject AlertDispatcher dispatcher;

    @Inject
    @ConfigProperty(name = "alerts.cpu.percent", defaultValue = "85")
    double cpuPercent;

    @Inject
    @ConfigProperty(name = "alerts.memory.percent", defaultValue = "92")
    double memoryPercent;

    @Inject
    @ConfigProperty(name = "alerts.battery.percent", defaultValue = "20")
    double batteryPercent;

    @Inject
    @ConfigProperty(name = "alerts.disk-capacity.percent", defaultValue = "90")
    double diskCapacityPercent;

    @Inject
    @ConfigProperty(name = "alerts.cooldown-seconds", defaultValue = "300")
    int cooldownSeconds;


    private final Map<AlertKind, Instant> lastRaised = new ConcurrentHashMap<>();

    void onSnapshot(@Observes final MetricsSnapshot snapshot) {
        WindowStats window     = factory.currentWindow();
        boolean     windowFull = window.samples() >= factory.windowSeconds() * 0.8;

        if (windowFull && window.cpuAvg() >= cpuPercent) {
            raise(snapshot, AlertKind.CPU_HIGH, String.format(Locale.ROOT, "CPU averaged %.1f%% over the last %d s (threshold %.0f%%)", window.cpuAvg(), window.seconds(), cpuPercent));
            return;
        }
        if (windowFull && window.memoryAvg() >= memoryPercent) {
            raise(snapshot, AlertKind.MEMORY_HIGH, String.format(Locale.ROOT, "Memory averaged %.1f%% over the last %d s (threshold %.0f%%)", window.memoryAvg(), window.seconds(), memoryPercent));
            return;
        }
        var battery = snapshot.battery();
        if (battery != null && !battery.onACPower() && battery.percent() <= batteryPercent) {
            raise(snapshot, AlertKind.BATTERY_LOW, String.format(Locale.ROOT, "Battery at %.0f%% and not on AC power (threshold %.0f%%)", battery.percent(), batteryPercent));
            return;
        }
        var disk = snapshot.disk();
        if (disk.totalBytes() > 0) {
            double used = 100.0 * disk.usedBytes() / disk.totalBytes();
            if (used >= diskCapacityPercent) {
                raise(snapshot, AlertKind.DISK_FULL, String.format(Locale.ROOT, "Disk is %.1f%% full (threshold %.0f%%)", used, diskCapacityPercent));
            }
        }
    }

    private void raise(final MetricsSnapshot snapshot, final AlertKind kind, final String detail) {
        final Instant last = lastRaised.get(kind);
        if (last != null && Duration.between(last, snapshot.timestamp()).getSeconds() < cooldownSeconds) { return; }
        final Optional<HealthAlert> alert = factory.create(kind, detail);
        if (alert.isPresent() && dispatcher.dispatch(alert.get())) {
            lastRaised.put(kind, snapshot.timestamp()); // Only start the cooldown once the agent actually took the alert. If it was busy, the condition is checked again on the next snapshot.
            LOG.info(() -> "Alert raised: " + kind + " - " + detail);
        }
    }
}
