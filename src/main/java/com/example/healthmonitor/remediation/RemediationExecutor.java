package com.example.healthmonitor.remediation;

import com.example.healthmonitor.agent.AssessmentStore;
import com.example.healthmonitor.metrics.MetricsBuffer;
import com.example.healthmonitor.metrics.MetricsClient;
import com.example.healthmonitor.model.AlertKind;
import com.example.healthmonitor.model.AssessmentRecord;
import com.example.healthmonitor.model.CommandResult;
import com.example.healthmonitor.model.MetricsSnapshot;
import com.example.healthmonitor.model.Proposal;
import com.example.healthmonitor.model.ProposalStatus;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Carries out a proposal after a person approved it. This is the only place that acts on the
 * outside world, and it is not part of the agent workflow: the workflow stops at "propose".
 *
 * <p>The action is sent to the monitored machine over the same WebSocket and the machine answers
 * with a result. The real System Monitor app is read-only and never answers, which is reported
 * as a failure instead of a success. A few seconds later the executor measures again and records
 * whether the action helped.
 */
@ApplicationScoped
public class RemediationExecutor {

    private static final Logger LOG = Logger.getLogger(RemediationExecutor.class.getName());

    private static final long ANSWER_TIMEOUT_SECONDS = 5;
    private static final long VERIFY_AFTER_SECONDS = 6;

    @Inject
    MetricsClient client;

    @Inject
    MetricsBuffer buffer;

    @Inject
    AssessmentStore store;

    @Resource(lookup = "java:comp/DefaultManagedScheduledExecutorService")
    ManagedScheduledExecutorService scheduler;

    /** @param record a record whose pending proposal was claimed with {@code store.claimPending} */
    public AssessmentRecord execute(AssessmentRecord record) {
        Proposal proposal = record.proposal();
        Double before = buffer.latest().map(s -> value(record.kind(), s)).orElse(null);

        ProposalStatus status;
        String message;
        try {
            CommandResult answer = client.sendCommand(proposal.action(), proposal.pid(), ANSWER_TIMEOUT_SECONDS).get();
            status = answer.ok() ? ProposalStatus.EXECUTED : ProposalStatus.FAILED;
            message = answer.message();
        } catch (IllegalStateException e) {
            status = ProposalStatus.FAILED;
            message = e.getMessage();
        } catch (ExecutionException e) {
            status = ProposalStatus.FAILED;
            message = e.getCause() instanceof TimeoutException
                    ? "No answer within " + ANSWER_TIMEOUT_SECONDS + " seconds. The connected source may be read-only: the"
                      + " real System Monitor does not accept commands. Use the Scenario Simulator for this step."
                    : "The command could not be sent: " + e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = ProposalStatus.FAILED;
            message = "Interrupted while waiting for the answer.";
        }
        ProposalStatus finalStatus = status;
        String finalMessage = message;
        LOG.info(() -> "Proposal " + record.id() + " " + proposal.action() + ": " + finalMessage);
        AssessmentRecord updated = store.decide(record.id(), finalStatus, finalMessage).orElse(record);
        if (finalStatus == ProposalStatus.EXECUTED) {
            scheduler.schedule(() -> verify(record.id(), record.kind(), before), VERIFY_AFTER_SECONDS, TimeUnit.SECONDS);
        }
        return updated;
    }

    public AssessmentRecord reject(AssessmentRecord record) {
        return store.decide(record.id(), ProposalStatus.REJECTED, "Rejected by the user.").orElse(record);
    }

    private void verify(long id, AlertKind kind, Double before) {
        try {
            Optional<MetricsSnapshot> latest = buffer.latest();
            if (latest.isEmpty() || before == null) {
                return;
            }
            double after = value(kind, latest.get());
            String label = label(kind);
            String text = after < before - 5
                    ? String.format(Locale.ROOT, "Verified %d seconds later: %s fell from %.1f%% to %.1f%%.",
                            VERIFY_AFTER_SECONDS, label, before, after)
                    : String.format(Locale.ROOT, "No clear improvement after %d seconds: %s went from %.1f%% to %.1f%%.",
                            VERIFY_AFTER_SECONDS, label, before, after);
            store.addVerification(id, text);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Verification failed: " + e, e);
        }
    }

    /** The metric the action is meant to improve. */
    private static String label(AlertKind kind) {
        return switch (kind) {
            case MEMORY_HIGH -> "Memory";
            case DISK_FULL -> "Disk capacity used";
            default -> "CPU";
        };
    }

    private static double value(AlertKind kind, MetricsSnapshot s) {
        return switch (kind) {
            case MEMORY_HIGH -> s.memory().percent();
            case DISK_FULL -> s.disk().totalBytes() > 0 ? 100.0 * s.disk().usedBytes() / s.disk().totalBytes() : 0;
            default -> s.cpu().percent();
        };
    }
}
