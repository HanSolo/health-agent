package com.example.healthmonitor.model;

import java.time.Instant;
import java.util.List;

/**
 * A finished assessment as stored and shown on the live page. It is replaced in the store
 * (same id) when its proposal changes, for example after approval.
 *
 * @param source "llm" for a model answer, "rules" for the rule-based fallback, "error" if the workflow failed
 * @param model  provider and model that were configured, e.g. "ollama:gemma3:4b"
 * @param millis time spent waiting for the LLM, plan and assessment together
 * @param trace  the steps the agent took, in order
 */
public record AssessmentRecord(long id, Instant at, AlertKind kind, String detail, Severity severity, String summary, String likelyCause, String recommendation, String source, String model, long millis, WindowStats window, List<TraceStep> trace, Proposal proposal) {
    public AssessmentRecord withProposal(Proposal newProposal) {
        return new AssessmentRecord(id, at, kind, detail, severity, summary, likelyCause, recommendation, source, model, millis, window, trace, newProposal);
    }
}
