package com.example.healthmonitor.model;

import java.util.List;

/**
 * Which evidence tools to run. Passed to the investigation step by type.
 *
 * @param tools   tool names, in the order they run
 * @param reason  why these tools, in the model's words, or why the default plan was used
 * @param fromLlm false when the model's plan was missing or unusable and the default plan was used
 */
public record InvestigationPlan(List<String> tools, String reason, boolean fromLlm) { }
