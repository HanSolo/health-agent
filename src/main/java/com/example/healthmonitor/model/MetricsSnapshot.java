package com.example.healthmonitor.model;

import java.time.Instant;
import java.util.List;

/**
 * One message from the Swift system monitor (JSON contract version 1).
 *
 * <p>{@code battery} is null on Macs without an internal battery, and {@code topProcesses}
 * is null when the Mac app cannot list processes. Unknown JSON properties are ignored,
 * so the Swift side can add fields without breaking this client.
 *
 * <p>{@code source} is null for the real System Monitor app and "simulator" for the scenario
 * simulator, so the page can say that the data is simulated.
 */
public record MetricsSnapshot(String type, int version, Instant timestamp, String source, Cpu cpu, Memory memory, Network network, Disk disk, Battery battery, TopProcesses topProcesses) {

    /** {@code percent} is overall load; process percentages are relative to ONE core. */
    public record Cpu(double percent, int cores) { }

    public record Memory(double percent, long usedBytes, long totalBytes) { }

    /** {@code percent} is relative to {@code scaleMaxBytesPerSec}. */
    public record Network(double percent, double rxBytesPerSec, double txBytesPerSec, double scaleMaxBytesPerSec) { }

    /** {@code percent} is I/O throughput relative to {@code scaleMaxBytesPerSec}, not capacity. */
    public record Disk(double percent, double readBytesPerSec, double writeBytesPerSec, long usedBytes, long totalBytes, double scaleMaxBytesPerSec) { }

    /** {@code minutesRemaining} is null while macOS has no estimate. */
    public record Battery(double percent, boolean charging, boolean onACPower, Integer minutesRemaining) { }

    public record ProcessEntry(int pid, String name, double cpuPercent, long memoryBytes) { }

    public record TopProcesses(List<ProcessEntry> cpu, List<ProcessEntry> memory) { }
}
