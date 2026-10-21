package com.example.healthmonitor.model;

public enum ProposalStatus {
    NONE,         // The agent suggests nothing to do.
    PENDING,      // Waiting for a person to approve or reject.
    BLOCKED,      // A guardrail refused the suggestion, so no button is shown.
    EXECUTED,     // Approved, sent to the monitored machine and acknowledged.
    FAILED,       // Approved, but the machine refused it, did not answer, or the send failed.
    REJECTED
}
