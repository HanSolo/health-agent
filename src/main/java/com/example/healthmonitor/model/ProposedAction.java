package com.example.healthmonitor.model;

/**
 * What the model suggests doing. This is untrusted: it is checked by the guardrails before
 * a person ever sees an Approve button.
 *
 * @param action "terminate_process", "purge_temp_files" or "none"
 * @param pid    only for terminate_process
 * @param reason one sentence from the model
 */
public record ProposedAction(String action, Integer pid, String reason) {
    public boolean isNone() {
        return action == null || action.isBlank() || "none".equalsIgnoreCase(action.strip());
    }
}
