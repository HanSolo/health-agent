package com.example.healthmonitor.agent;

import com.example.healthmonitor.model.AlertKind;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The team's rules, loaded from {@code runbook.json}. It does two jobs: the agent can read the
 * guidance as evidence, and the {@link Guardrails} use the allowed actions as policy.
 *
 * <p>Entries are checked in file order and the first match wins, so specific entries (system
 * processes, build tools) come before the general ones. If the file cannot be read the list is
 * empty and every proposed action is blocked.
 */
@ApplicationScoped
public class Runbook {

    private static final Logger LOG = Logger.getLogger(Runbook.class.getName());

    /** @param processPattern null for an entry that applies to the whole alert kind */
    public record Entry(String id, String title, Set<String> kinds, Pattern processPattern,
                        String guidance, Set<String> allowedActions) {

        boolean appliesTo(AlertKind kind) {
            return kinds.contains(kind.name());
        }

        boolean matchesProcess(String processName) {
            return processPattern == null || (processName != null && processPattern.matcher(processName).matches());
        }
    }

    private volatile List<Entry> entries = List.of();

    @PostConstruct
    void load() {
        try (InputStream in = Runbook.class.getResourceAsStream("/runbook.json")) {
            if (in == null) {
                LOG.severe("runbook.json not found on the classpath: every proposed action will be blocked");
                return;
            }
            JsonArray array;
            try (var reader = Json.createReader(in)) {
                array = reader.readArray();
            }
            List<Entry> loaded = new ArrayList<>();
            for (JsonValue value : array) {
                JsonObject o = value.asJsonObject();
                loaded.add(new Entry(
                        o.getString("id"),
                        o.getString("title"),
                        strings(o, "kinds"),
                        o.containsKey("processPattern") ? Pattern.compile(o.getString("processPattern")) : null,
                        o.getString("guidance"),
                        strings(o, "allowedActions")));
            }
            entries = List.copyOf(loaded);
            LOG.info(() -> "Runbook loaded with " + entries.size() + " entries");
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not read runbook.json, every proposed action will be blocked: " + e, e);
        }
    }

    private static Set<String> strings(JsonObject o, String key) {
        Set<String> result = new LinkedHashSet<>();
        if (o.containsKey(key)) {
            for (JsonValue v : o.getJsonArray(key)) {
                result.add(((JsonString) v).getString());
            }
        }
        return result;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** The first entry that applies to this alert kind and process. */
    public Optional<Entry> firstFor(AlertKind kind, String processName) {
        return entries.stream().filter(e -> e.appliesTo(kind) && e.matchesProcess(processName)).findFirst();
    }

    /** True when some general entry for this kind (no process pattern) allows the action. */
    public Optional<Entry> generalEntryAllowing(AlertKind kind, String action) {
        return entries.stream()
                .filter(e -> e.appliesTo(kind) && e.processPattern() == null && e.allowedActions().contains(action))
                .findFirst();
    }
}
