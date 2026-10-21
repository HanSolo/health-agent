package com.example.healthmonitor.agent;

import com.example.healthmonitor.model.HealthAlert;
import com.example.healthmonitor.model.MetricsSnapshot;
import com.example.healthmonitor.model.Proposal;
import com.example.healthmonitor.model.ProposalStatus;
import com.example.healthmonitor.model.ProposedAction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks what the model proposes before a person sees an Approve button.
 *
 * <p>The model's output is untrusted. This class is plain code on purpose: it never asks the
 * model whether its own suggestion is safe. It checks three things: the action is on a short
 * allow-list, the process really appeared in the measurements (so the model cannot invent a
 * pid), and the runbook allows that action for this kind of alert and this process.
 */
@ApplicationScoped
public class Guardrails {

    public static final String TERMINATE = "terminate_process";
    public static final String PURGE = "purge_temp_files";
    private static final Set<String> ALLOWED_ACTIONS = Set.of(TERMINATE, PURGE);

    @Inject
    Runbook runbook;

    public Proposal review(HealthAlert alert, ProposedAction proposed) {
        if (proposed == null || proposed.isNone()) {
            return Proposal.none();
        }
        String action = proposed.action().strip().toLowerCase();
        String reason = proposed.reason() == null ? "" : proposed.reason();

        if (!ALLOWED_ACTIONS.contains(action)) {
            return blocked(action, proposed.pid(), null, reason, "'" + action + "' is not on the allow-list of actions.");
        }

        if (TERMINATE.equals(action)) {
            return reviewTerminate(alert, proposed, reason);
        }

        // purge_temp_files has no process: the runbook must allow it for this kind of alert.
        Optional<Runbook.Entry> entry = runbook.generalEntryAllowing(alert.kind(), PURGE);
        if (entry.isEmpty()) {
            return blocked(action, null, null, reason, "No runbook entry allows purging temporary files for " + alert.kind() + ".");
        }
        return new Proposal(ProposalStatus.PENDING, action, null, null, reason,
                "Allowed by runbook entry '" + entry.get().title() + "'.", null, null, null);
    }

    private Proposal reviewTerminate(HealthAlert alert, ProposedAction proposed, String reason) {
        Integer pid = proposed.pid();
        if (pid == null) {
            return blocked(TERMINATE, null, null, reason, "The proposal names no process id.");
        }
        Optional<MetricsSnapshot.ProcessEntry> process = find(alert.latest(), pid);
        if (process.isEmpty()) {
            return blocked(TERMINATE, pid, null, reason,
                    "Process id " + pid + " is not in the latest measurements, so the model may have invented it.");
        }
        String name = process.get().name();
        Optional<Runbook.Entry> entry = runbook.firstFor(alert.kind(), name);
        if (entry.isEmpty()) {
            return blocked(TERMINATE, pid, name, reason, "No runbook entry covers " + name + " for " + alert.kind() + ".");
        }
        if (!entry.get().allowedActions().contains(TERMINATE)) {
            return blocked(TERMINATE, pid, name, reason,
                    "Runbook entry '" + entry.get().title() + "' does not allow terminating " + name + ".");
        }
        return new Proposal(ProposalStatus.PENDING, TERMINATE, pid, name, reason,
                "Allowed by runbook entry '" + entry.get().title() + "'.", null, null, null);
    }

    private static Optional<MetricsSnapshot.ProcessEntry> find(MetricsSnapshot snapshot, int pid) {
        if (snapshot == null || snapshot.topProcesses() == null) {
            return Optional.empty();
        }
        List<MetricsSnapshot.ProcessEntry> all = new ArrayList<>();
        if (snapshot.topProcesses().cpu() != null) {
            all.addAll(snapshot.topProcesses().cpu());
        }
        if (snapshot.topProcesses().memory() != null) {
            all.addAll(snapshot.topProcesses().memory());
        }
        return all.stream().filter(p -> p.pid() == pid).findFirst();
    }

    private static Proposal blocked(String action, Integer pid, String name, String reason, String why) {
        return new Proposal(ProposalStatus.BLOCKED, action, pid, name, reason, why, null, null, null);
    }
}
