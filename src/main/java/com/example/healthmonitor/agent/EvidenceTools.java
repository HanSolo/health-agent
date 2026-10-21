package com.example.healthmonitor.agent;

import com.example.healthmonitor.metrics.MetricsBuffer;
import com.example.healthmonitor.model.AlertKind;
import com.example.healthmonitor.model.AssessmentRecord;
import com.example.healthmonitor.model.HealthAlert;
import com.example.healthmonitor.model.MetricsSnapshot;
import com.example.healthmonitor.model.ToolResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The agent's evidence tools. Each one is a plain method over data the application already
 * has, with no side effects, so the agent can run any of them safely. The model chooses
 * which ones to run in the planning step and reads their text results in the assessment step.
 *
 * <p>These are ordinary CDI code, not a feature of the Agentic AI draft: the draft's M1 API
 * has no tool calling, so the "tool use" is done by the workflow itself.
 */
@ApplicationScoped
public class EvidenceTools {

    private static final Logger LOG = Logger.getLogger(EvidenceTools.class.getName());

    public static final String METRIC_TREND = "metric_trend";
    public static final String PROCESS_HISTORY = "process_history";
    public static final String RECENT_ALERTS = "recent_alerts";
    public static final String RUNBOOK_LOOKUP = "runbook_lookup";

    /** Tool name to the one-line description shown to the model. Order is the default run order. */
    public static final Map<String, String> DESCRIPTIONS = descriptions();

    public static final List<String> ALL = List.copyOf(DESCRIPTIONS.keySet());

    private static Map<String, String> descriptions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(METRIC_TREND, "how the affected metric moved over the last two minutes: sudden or gradual, and for how long it has been high");
        m.put(PROCESS_HISTORY, "which processes were consistently heavy, and which ones kept growing in memory");
        m.put(RECENT_ALERTS, "whether this kind of alert happened before in this session and what was decided");
        m.put(RUNBOOK_LOOKUP, "the team's rules for this situation, including which actions are allowed");
        return m;
    }

    private static final Duration HISTORY = Duration.ofSeconds(120);
    private static final long MEMORY_GROWTH_MIN_BYTES = 200_000_000L;

    @Inject
    MetricsBuffer buffer;

    @Inject
    AssessmentStore store;

    @Inject
    Runbook runbook;

    public ToolResult run(String tool, HealthAlert alert) {
        long started = System.nanoTime();
        String text;
        try {
            text = switch (tool) {
                case METRIC_TREND -> metricTrend(alert);
                case PROCESS_HISTORY -> processHistory();
                case RECENT_ALERTS -> recentAlerts(alert);
                case RUNBOOK_LOOKUP -> runbookLookup(alert);
                default -> "Unknown tool.";
            };
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Tool " + tool + " failed: " + e, e);
            text = "The tool failed: " + e;
        }
        return new ToolResult(tool, text, (System.nanoTime() - started) / 1_000_000);
    }

    // --- metric_trend ---

    private record Metric(String label, Function<MetricsSnapshot, Double> read) {
    }

    private static List<Metric> metricsFor(AlertKind kind) {
        Metric cpu = new Metric("CPU", s -> s.cpu().percent());
        Metric memory = new Metric("Memory", s -> s.memory().percent());
        Metric disk = new Metric("Disk capacity used", s ->
                s.disk().totalBytes() > 0 ? 100.0 * s.disk().usedBytes() / s.disk().totalBytes() : null);
        Metric battery = new Metric("Battery", s -> s.battery() == null ? null : s.battery().percent());
        return switch (kind) {
            case CPU_HIGH -> List.of(cpu);
            case MEMORY_HIGH -> List.of(memory);
            case DISK_FULL -> List.of(disk);
            case BATTERY_LOW -> List.of(battery, cpu);
            case MANUAL -> List.of(cpu, memory);
        };
    }

    private String metricTrend(HealthAlert alert) {
        List<MetricsSnapshot> window = buffer.window(HISTORY);
        if (window.size() < 2) {
            return "Not enough history yet.";
        }
        List<String> lines = new ArrayList<>();
        for (Metric metric : metricsFor(alert.kind())) {
            lines.add(describe(metric, window));
        }
        return String.join("\n", lines);
    }

    private static String describe(Metric metric, List<MetricsSnapshot> window) {
        List<Double> values = new ArrayList<>();
        List<Instant> times = new ArrayList<>();
        for (MetricsSnapshot s : window) {
            Double v = metric.read().apply(s);
            if (v != null) {
                values.add(v);
                times.add(s.timestamp());
            }
        }
        int n = values.size();
        if (n < 2) {
            return metric.label() + ": no data.";
        }
        double now = values.get(n - 1);
        double first = values.get(0);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        long spanSeconds = Math.max(1, Duration.between(times.get(0), times.get(n - 1)).getSeconds());

        // How long has the value stayed close to where it is now?
        double tolerance = 5.0;
        int start = n - 1;
        while (start > 0 && Math.abs(values.get(start - 1) - now) <= tolerance) {
            start--;
        }
        long steadySeconds = Duration.between(times.get(start), times.get(n - 1)).getSeconds();

        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "%s: %.1f%% now. %d s ago it was %.1f%%. Lowest %.1f%%, highest %.1f%%.",
                metric.label(), now, spanSeconds, first, min, max));
        text.append(String.format(Locale.ROOT, " It has stayed within %.0f points of the current value for %d s",
                tolerance, steadySeconds));
        if (start > 0) {
            double before = 0;
            for (int i = 0; i < start; i++) {
                before += values.get(i);
            }
            text.append(String.format(Locale.ROOT, "; before that it averaged %.1f%%", before / start));
        }
        text.append(String.format(Locale.ROOT, ". Change over the window: %+.1f points (%+.1f per minute).",
                now - first, (now - first) * 60.0 / spanSeconds));
        return text.toString();
    }

    // --- process_history ---

    private static final class ProcessStats {
        final int pid;
        final String name;
        int samples;
        double cpuSum;
        double cpuMax;
        long firstMemory = -1;
        long lastMemory;
        Instant firstSeen;
        Instant lastSeen;

        ProcessStats(int pid, String name) {
            this.pid = pid;
            this.name = name;
        }

        double cpuAverage() {
            return samples == 0 ? 0 : cpuSum / samples;
        }

        long memoryGrowth() {
            return lastMemory - firstMemory;
        }
    }

    private String processHistory() {
        List<MetricsSnapshot> window = buffer.window(HISTORY);
        Map<Integer, ProcessStats> stats = new LinkedHashMap<>();
        int withProcesses = 0;
        for (MetricsSnapshot s : window) {
            if (s.topProcesses() == null) {
                continue;
            }
            withProcesses++;
            // A process can be in both lists of one snapshot: count it once.
            Map<Integer, MetricsSnapshot.ProcessEntry> perSnapshot = new LinkedHashMap<>();
            if (s.topProcesses().cpu() != null) {
                s.topProcesses().cpu().forEach(p -> perSnapshot.put(p.pid(), p));
            }
            if (s.topProcesses().memory() != null) {
                s.topProcesses().memory().forEach(p -> perSnapshot.putIfAbsent(p.pid(), p));
            }
            for (MetricsSnapshot.ProcessEntry p : perSnapshot.values()) {
                ProcessStats st = stats.computeIfAbsent(p.pid(), pid -> new ProcessStats(pid, p.name()));
                st.samples++;
                st.cpuSum += p.cpuPercent();
                st.cpuMax = Math.max(st.cpuMax, p.cpuPercent());
                if (st.firstMemory < 0) {
                    st.firstMemory = p.memoryBytes();
                    st.firstSeen = s.timestamp();
                }
                st.lastMemory = p.memoryBytes();
                st.lastSeen = s.timestamp();
            }
        }
        if (stats.isEmpty()) {
            return "No process information in the recent measurements.";
        }

        final int snapshotsWithProcesses = withProcesses;
        StringBuilder text = new StringBuilder("Heaviest by CPU over the last two minutes:\n");
        stats.values().stream()
                .sorted((a, b) -> Double.compare(b.cpuAverage(), a.cpuAverage()))
                .limit(3)
                .forEach(p -> text.append(String.format(Locale.ROOT,
                        "- %s (pid %d): in the top lists for %d of %d measurements, average %.0f%% of one core, peak %.0f%%, memory %s.%n",
                        p.name, p.pid, p.samples, snapshotsWithProcesses, p.cpuAverage(), p.cpuMax, gb(p.lastMemory))));

        List<ProcessStats> growing = stats.values().stream()
                .filter(p -> p.memoryGrowth() >= MEMORY_GROWTH_MIN_BYTES)
                .sorted((a, b) -> Long.compare(b.memoryGrowth(), a.memoryGrowth()))
                .limit(3)
                .toList();
        if (growing.isEmpty()) {
            text.append("No process grew its memory by more than 200 MB.");
        } else {
            text.append("Processes whose memory grew:\n");
            for (ProcessStats p : growing) {
                long seconds = Math.max(1, Duration.between(p.firstSeen, p.lastSeen).getSeconds());
                text.append(String.format(Locale.ROOT, "- %s (pid %d): %s to %s in %d s (about %s per second).%n",
                        p.name, p.pid, gb(p.firstMemory), gb(p.lastMemory), seconds, gb(p.memoryGrowth() / seconds)));
            }
        }
        return text.toString().strip();
    }

    private static String gb(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0);
    }

    // --- recent_alerts ---

    private String recentAlerts(HealthAlert alert) {
        List<AssessmentRecord> earlier = store.list().stream()
                .filter(r -> r.kind() == alert.kind() && !"error".equals(r.source()))
                .limit(5)
                .toList();
        if (earlier.isEmpty()) {
            return "No earlier " + alert.kind() + " assessments in this session.";
        }
        StringBuilder text = new StringBuilder(earlier.size() + " earlier " + alert.kind() + " assessment(s), newest first:\n");
        Instant now = Instant.now();
        for (AssessmentRecord r : earlier) {
            long minutes = Math.max(0, Duration.between(r.at(), now).toMinutes());
            text.append("- ").append(minutes).append(" min ago: ").append(r.severity())
                    .append(", \"").append(r.summary()).append("\"");
            if (r.proposal() != null && r.proposal().action() != null) {
                text.append("; proposed ").append(r.proposal().action());
                if (r.proposal().processName() != null) {
                    text.append(" on ").append(r.proposal().processName());
                }
                text.append(" (").append(r.proposal().status()).append(")");
            }
            text.append("\n");
        }
        return text.toString().strip();
    }

    // --- runbook_lookup ---

    private String runbookLookup(HealthAlert alert) {
        Set<String> candidates = new LinkedHashSet<>();
        MetricsSnapshot latest = alert.latest();
        if (latest != null && latest.topProcesses() != null) {
            if (latest.topProcesses().cpu() != null) {
                latest.topProcesses().cpu().forEach(p -> candidates.add(p.name()));
            }
            if (latest.topProcesses().memory() != null) {
                latest.topProcesses().memory().forEach(p -> candidates.add(p.name()));
            }
        }
        StringBuilder text = new StringBuilder();
        for (Runbook.Entry entry : runbook.entries()) {
            if (!entry.appliesTo(alert.kind())) {
                continue;
            }
            String applies;
            if (entry.processPattern() == null) {
                applies = "applies to the situation";
            } else {
                List<String> matches = candidates.stream().filter(entry::matchesProcess).toList();
                if (matches.isEmpty()) {
                    continue;
                }
                applies = "applies to " + String.join(", ", matches);
            }
            text.append("- ").append(entry.title()).append(" (").append(applies).append("): ")
                    .append(entry.guidance()).append(" Allowed actions: ")
                    .append(entry.allowedActions().isEmpty() ? "none" : String.join(", ", entry.allowedActions()))
                    .append(".\n");
        }
        return text.isEmpty() ? "No runbook entry applies." : text.toString().strip();
    }
}
