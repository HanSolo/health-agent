package com.example.healthmonitor.agent;

import com.example.healthmonitor.model.AssessmentRecord;
import com.example.healthmonitor.model.HealthAlert;
import com.example.healthmonitor.model.HealthAssessment;
import com.example.healthmonitor.model.Proposal;
import com.example.healthmonitor.model.ProposalStatus;
import com.example.healthmonitor.model.Severity;
import com.example.healthmonitor.model.TraceStep;
import com.example.healthmonitor.rest.StreamHub;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;


/**
 * The most recent assessments, newest first. Every new or changed one is pushed to the live page.
 * A record keeps its id when its proposal changes (approved, rejected, verified), so the page
 * can update the card in place.
 */
@ApplicationScoped
public class AssessmentStore {
    private static final int MAX = 20;

    @Inject StreamHub hub;

    // All access is synchronized on `records`.
    private final List<AssessmentRecord> records  = new ArrayList<>();
    private final Set<Long>              deciding = new HashSet<>();
    private final AtomicLong             ids      = new AtomicLong();

    public void add(HealthAlert alert, HealthAssessment a, Proposal proposal, List<TraceStep> trace,
                    String source, String model, long millis) {
        insert(new AssessmentRecord(ids.incrementAndGet(), Instant.now(), alert.kind(), alert.detail(),
                a.severity(), a.summary(), a.likelyCause(), a.recommendation(),
                source, model, millis, alert.window(), trace, proposal));
    }

    public void addError(final HealthAlert alert, final String message, final String model, final List<TraceStep> trace) {
        insert(new AssessmentRecord(ids.incrementAndGet(), Instant.now(), alert.kind(), alert.detail(), Severity.LOW, "The analysis failed.", message, "See the server log.", "error", model, 0, alert.window(), trace, Proposal.none()));
    }

    /** Newest first. */
    public List<AssessmentRecord> list() {
        synchronized (records) {
            return new ArrayList<>(records);
        }
    }

    public Optional<AssessmentRecord> find(final long id) {
        synchronized (records) {
            return records.stream().filter(r -> r.id() == id).findFirst();
        }
    }

    /**
     * Claims a pending proposal for approval or rejection. Only one caller can win, so a double
     * click cannot run the same action twice.
     */
    public Optional<AssessmentRecord> claimPending(final long id) {
        synchronized (records) {
            final Optional<AssessmentRecord> record = records.stream().filter(r -> r.id() == id).findFirst();
            if (record.isEmpty() || record.get().proposal().status() != ProposalStatus.PENDING || !deciding.add(id)) {
                return Optional.empty();
            }
            return record;
        }
    }

    /** Stores the outcome of a decision and releases the claim. */
    public Optional<AssessmentRecord> decide(final long id, final ProposalStatus status, final String result) {
        final Optional<AssessmentRecord> updated = update(id, p -> p.with(status, result, Instant.now()));
        synchronized (records) {
            deciding.remove(id);
        }
        return updated;
    }

    public void addVerification(final long id, final String text) {
        update(id, p -> p.withVerification(text));
    }

    private Optional<AssessmentRecord> update(final long id, final UnaryOperator<Proposal> change) {
        AssessmentRecord updated = null;
        synchronized (records) {
            for (int i = 0; i < records.size(); i++) {
                if (records.get(i).id() == id) {
                    updated = records.get(i).withProposal(change.apply(records.get(i).proposal()));
                    records.set(i, updated);
                    break;
                }
            }
        }
        if (updated != null) { hub.publish("assessment", hub.toJson(updated)); }
        return Optional.ofNullable(updated);
    }

    private void insert(final AssessmentRecord record) {
        synchronized (records) {
            records.add(0, record);
            while (records.size() > MAX) {
                records.remove(records.size() - 1);
            }
        }
        hub.publish("assessment", hub.toJson(record));
    }
}
