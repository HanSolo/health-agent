package com.example.healthmonitor.model;

import java.time.Instant;

/**
 * A remediation the agent proposes, and what became of it.
 *
 * @param guardrail   why the guardrails blocked it, or which policy allowed it
 * @param result      the machine's answer after approval
 * @param verification what the metrics showed a few seconds after the action
 */
public record Proposal(ProposalStatus status, String action, Integer pid, String processName, String reason, String guardrail, String result, String verification, Instant decidedAt) {

    public static Proposal none() {
        return new Proposal(ProposalStatus.NONE, null, null, null, null, null, null, null, null);
    }

    /** One line for the evidence trace. */
    public String describe() {
        return switch (status) {
            case NONE    -> "The agent proposes no action.";
            case PENDING -> "Proposal accepted for approval: " + action + (processName == null ? "" : " on " + processName) + ". " + guardrail;
            default      -> "Proposal blocked: " + guardrail;
        };
    }

    public Proposal with(ProposalStatus newStatus, String newResult, Instant when) {
        return new Proposal(newStatus, action, pid, processName, reason, guardrail, newResult, verification, when);
    }

    public Proposal withVerification(String text) {
        return new Proposal(status, action, pid, processName, reason, guardrail, result, text, decidedAt);
    }
}
