package com.example.healthmonitor.agent;

import com.example.healthmonitor.model.Evidence;
import com.example.healthmonitor.model.HealthAlert;
import com.example.healthmonitor.model.HealthAssessment;
import com.example.healthmonitor.model.InvestigationPlan;
import com.example.healthmonitor.model.MetricsSnapshot;
import com.example.healthmonitor.model.ProposedAction;
import com.example.healthmonitor.model.Severity;
import com.example.healthmonitor.model.ToolResult;
import com.example.healthmonitor.model.WindowStats;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Prompt building, defensive parsing of the model's answers, and the rule-based fallback.
 * Everything the model says is treated as untrusted text.
 */
final class AssessmentSupport {

    private static final int MAX_FIELD      = 500;
    private static final int MAX_PLAN_TOOLS = 4;

    private AssessmentSupport() {
    }

    // --- Prompts ---

    /** Step 1 of the workflow: let the model choose which tools are worth running. */
    static String planPrompt(HealthAlert alert) {
        StringBuilder p = new StringBuilder();
        p.append("You are the planning step of a monitoring agent for a Mac. A measurement needs a closer look.\n");
        p.append("Choose which tools to run before the situation is judged. Only choose tools that will help.\n\n");
        p.append("Trigger: ").append(alert.kind()).append(" - ").append(alert.detail()).append("\n");
        appendProcessNames(p, alert.latest());
        p.append("\nAvailable tools:\n");
        EvidenceTools.DESCRIPTIONS.forEach((name, description) ->
                p.append("- ").append(name).append(": ").append(description).append("\n"));
        p.append("\nReply with a single JSON object and nothing else: no markdown, no code fences.\n");
        p.append("The keys are tools (an array of 2 to ").append(MAX_PLAN_TOOLS)
                .append(" tool names from the list above) and reason (one sentence, a string).\n");
        return p.toString();
    }

    /** Step 3 of the workflow: judge the situation, using the evidence the tools returned. */
    static String assessPrompt(HealthAlert alert, Evidence evidence) {
        StringBuilder p = new StringBuilder(facts(alert));
        p.append("\nEvidence gathered by tools:\n");
        for (ToolResult r : evidence.results()) {
            p.append("\n[").append(r.tool()).append("]\n").append(r.summary()).append("\n");
        }
        p.append("\nFollow the runbook text in the evidence. If it says the load is expected, the severity is LOW and no action is proposed.\n");
        p.append("\nReply with a single JSON object and nothing else: no markdown, no code fences.\n");
        p.append("The keys are:\n");
        p.append("severity: a string, one of LOW, MEDIUM, HIGH. LOW means nothing needs attention.\n");
        p.append("summary: one sentence saying what is going on.\n");
        p.append("likelyCause: one sentence, naming a process if it is the likely cause.\n");
        p.append("recommendation: one concrete action for the user.\n");
        p.append("proposedAction: an object with the keys action, pid and reason.\n");
        p.append("  action is one of terminate_process, purge_temp_files, none.\n");
        p.append("  pid is the process id for terminate_process. Use only a pid from the process lists above. Use null otherwise.\n");
        p.append("  reason is one sentence.\n");
        p.append("Use none when no action is appropriate or the runbook does not allow it.\n");
        return p.toString();
    }

    private static String facts(HealthAlert alert) {
        WindowStats w = alert.window();
        MetricsSnapshot s = alert.latest();
        StringBuilder p = new StringBuilder();
        p.append("You are assessing the health of a Mac from measurements taken by a monitoring app.\n");
        p.append("Use only the facts below. If they do not explain something, say the cause is unclear.\n\n");

        p.append("Trigger: ").append(alert.kind()).append(" - ").append(alert.detail()).append("\n");
        if (alert.manual()) {
            p.append("The user asked for a general health check, so nothing has to be wrong.\n");
        }
        p.append("\n");

        p.append(String.format(Locale.ROOT, "Last %d seconds (%d samples), average / peak in percent:%n",
                w.seconds(), w.samples()));
        p.append(String.format(Locale.ROOT, "- CPU: %.0f / %.0f (percent of all %d cores together)%n",
                w.cpuAvg(), w.cpuMax(), s.cpu().cores()));
        p.append(String.format(Locale.ROOT, "- Memory used: %.0f / %.0f (%s of %s)%n",
                w.memoryAvg(), w.memoryMax(), gib(s.memory().usedBytes()), gib(s.memory().totalBytes())));
        p.append(String.format(Locale.ROOT, "- Network: %.0f / %.0f of the scale maximum%n",
                w.networkAvg(), w.networkMax()));
        p.append(String.format(Locale.ROOT, "- Disk I/O: %.0f / %.0f of the scale maximum%n",
                w.diskAvg(), w.diskMax()));
        if (s.disk().totalBytes() > 0) {
            p.append(String.format(Locale.ROOT, "- Disk capacity used: %.0f%%%n",
                    100.0 * s.disk().usedBytes() / s.disk().totalBytes()));
        }
        if (s.battery() != null) {
            p.append(String.format(Locale.ROOT, "- Battery: %.0f%%, %s%n", s.battery().percent(),
                    s.battery().onACPower() ? "on AC power" : "on battery power"));
            if (w.batteryStartPercent() != null && w.batteryEndPercent() != null) {
                p.append(String.format(Locale.ROOT, "  (was %.0f%% at the start of the window)%n",
                        w.batteryStartPercent()));
            }
        }

        if (s.topProcesses() != null) {
            appendProcesses(p, "Top processes by CPU (percent of one core, so above 100 is possible)",
                    s.topProcesses().cpu(), false);
            appendProcesses(p, "Top processes by memory", s.topProcesses().memory(), true);
        }
        return p.toString();
    }

    private static void appendProcessNames(StringBuilder p, MetricsSnapshot s) {
        if (s == null || s.topProcesses() == null) {
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        if (s.topProcesses().cpu() != null) {
            s.topProcesses().cpu().forEach(e -> names.add(e.name()));
        }
        if (s.topProcesses().memory() != null) {
            s.topProcesses().memory().forEach(e -> names.add(e.name()));
        }
        if (!names.isEmpty()) {
            p.append("Busiest processes right now: ").append(String.join(", ", names)).append("\n");
        }
    }

    private static void appendProcesses(StringBuilder p, String title,
                                        List<MetricsSnapshot.ProcessEntry> list, boolean memory) {
        if (list == null || list.isEmpty()) {
            return;
        }
        p.append("\n").append(title).append(":\n");
        for (var e : list) {
            if (memory) {
                p.append(String.format(Locale.ROOT, "- %s (pid %d): %s%n", e.name(), e.pid(), gib(e.memoryBytes())));
            } else {
                p.append(String.format(Locale.ROOT, "- %s (pid %d): %.0f%%%n", e.name(), e.pid(), e.cpuPercent()));
            }
        }
    }

    private static String gib(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    // --- Parsing ---

    /** The JSON object between the first '{' and the last '}', or null. Models like to add chatter. */
    private static JsonObject extractObject(String reply) {
        if (reply == null) {
            return null;
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try (var reader = Json.createReader(new StringReader(reply.substring(start, end + 1)))) {
            return reader.readObject();
        } catch (JsonException | IllegalStateException e) {
            return null;
        }
    }

    /** @return the plan, or null when the reply is unusable (the caller then runs every tool) */
    static InvestigationPlan parsePlan(String reply) {
        JsonObject json = extractObject(reply);
        if (json == null || !(json.get("tools") instanceof JsonArray array)) {
            return null;
        }
        Set<String> chosen = new LinkedHashSet<>();
        for (JsonValue item : array) {
            if (item instanceof JsonString s) {
                String name = s.getString().strip().toLowerCase(Locale.ROOT);
                if (EvidenceTools.ALL.contains(name)) {
                    chosen.add(name);
                }
            }
            if (chosen.size() == MAX_PLAN_TOOLS) {
                break;
            }
        }
        if (chosen.isEmpty()) {
            return null;
        }
        String reason = text(json, "reason");
        return new InvestigationPlan(List.copyOf(chosen), reason.isBlank() ? "No reason given." : reason, true);
    }

    /** @return the assessment, or null when the reply cannot be used (the caller then falls back) */
    static HealthAssessment parse(String reply) {
        JsonObject json = extractObject(reply);
        if (json == null) {
            return null;
        }
        try {
            String summary = text(json, "summary");
            if (summary.isBlank()) {
                return null;
            }
            return new HealthAssessment(
                    severity(text(json, "severity")),
                    summary,
                    text(json, "likelyCause"),
                    text(json, "recommendation"),
                    proposed(json.get("proposedAction")));
        } catch (ClassCastException e) {
            return null;
        }
    }

    private static ProposedAction proposed(JsonValue value) {
        if (value instanceof JsonObject o) {
            return new ProposedAction(text(o, "action"), intOrNull(o, "pid"), text(o, "reason"));
        }
        if (value instanceof JsonString s) {
            return new ProposedAction(s.getString().strip(), null, "");
        }
        return null;
    }

    private static Integer intOrNull(JsonObject o, String key) {
        JsonValue v = o.get(key);
        if (v instanceof JsonNumber n && n.isIntegral()) {
            return n.intValue();
        }
        if (v instanceof JsonString s) {
            try {
                return Integer.valueOf(s.getString().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonObject json, String key) {
        JsonValue value = json.get(key);
        if (value == null || value.getValueType() == JsonValue.ValueType.NULL) {
            return "";
        }
        String text = value instanceof JsonString js ? js.getString() : value.toString();
        text = text.strip();
        return text.length() > MAX_FIELD ? text.substring(0, MAX_FIELD) + "..." : text;
    }

    private static Severity severity(String value) {
        try {
            return Severity.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Severity.MEDIUM;
        }
    }

    // --- Rule-based fallback ---

    private static MetricsSnapshot.ProcessEntry first(List<MetricsSnapshot.ProcessEntry> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
        }

    static HealthAssessment fallback(HealthAlert alert) {
        WindowStats w = alert.window();
        MetricsSnapshot s = alert.latest();
        MetricsSnapshot.ProcessEntry topCpu = s.topProcesses() == null ? null : first(s.topProcesses().cpu());
        MetricsSnapshot.ProcessEntry topMemory = s.topProcesses() == null ? null : first(s.topProcesses().memory());
        String cpuName = topCpu == null ? null : topCpu.name();
        String memoryName = topMemory == null ? null : topMemory.name();

        // The rules only suggest; the guardrails still decide whether the suggestion is allowed.
        ProposedAction cpuAction = topCpu != null && topCpu.cpuPercent() >= 150
                ? new ProposedAction("terminate_process", topCpu.pid(), "It is the heaviest CPU user.") : null;
        ProposedAction memoryAction = topMemory != null && topMemory.memoryBytes() >= 4_000_000_000L
                ? new ProposedAction("terminate_process", topMemory.pid(), "It uses the most memory.") : null;

        return switch (alert.kind()) {
            case CPU_HIGH -> new HealthAssessment(
                    w.cpuAvg() >= 95 ? Severity.HIGH : Severity.MEDIUM,
                    String.format(Locale.ROOT, "CPU has been busy: %.0f%% on average.", w.cpuAvg()),
                    cpuName == null ? "Unknown." : cpuName + " is using the most CPU.",
                    cpuName == null ? "Check Activity Monitor." : "Check whether " + cpuName + " is doing expected work.",
                    cpuAction);
            case MEMORY_HIGH -> new HealthAssessment(
                    w.memoryAvg() >= 97 ? Severity.HIGH : Severity.MEDIUM,
                    String.format(Locale.ROOT, "Memory use is high: %.0f%% on average.", w.memoryAvg()),
                    memoryName == null ? "Unknown." : memoryName + " uses the most memory.",
                    memoryName == null ? "Close applications you do not need." : "Consider quitting or restarting " + memoryName + ".",
                    memoryAction);
            case BATTERY_LOW -> new HealthAssessment(
                    s.battery() != null && s.battery().percent() <= 10 ? Severity.HIGH : Severity.MEDIUM,
                    "Battery is low and the Mac is not on AC power.",
                    cpuName == null ? "Unknown." : cpuName + " is the busiest process.",
                    "Plug in the charger.",
                    null);
            case DISK_FULL -> new HealthAssessment(
                    Severity.MEDIUM,
                    "The disk is nearly full.",
                    "Unknown.",
                    "Free up space, for example by emptying the Trash and removing large files.",
                    new ProposedAction("purge_temp_files", null, "Temporary files are the safest thing to remove."));
            case MANUAL -> new HealthAssessment(
                    w.cpuAvg() >= 85 || w.memoryAvg() >= 92 ? Severity.MEDIUM : Severity.LOW,
                    String.format(Locale.ROOT, "CPU %.0f%%, memory %.0f%% on average over the last %d seconds.",
                            w.cpuAvg(), w.memoryAvg(), w.seconds()),
                    "No unusual load found by the built-in rules.",
                    "Nothing to do.",
                    null);
        };
    }
}
