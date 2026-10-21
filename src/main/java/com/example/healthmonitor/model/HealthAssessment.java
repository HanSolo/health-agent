package com.example.healthmonitor.model;

/**
 * The agent's verdict on an alert. Passed between workflow phases by type.
 *
 * @param proposed what the model suggests doing about it, or null
 */
public record HealthAssessment(Severity severity, String summary, String likelyCause, String recommendation, ProposedAction proposed) { }
