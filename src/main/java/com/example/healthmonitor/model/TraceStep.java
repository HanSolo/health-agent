package com.example.healthmonitor.model;

/**
 * One line of the agent's visible reasoning trail.
 *
 * @param phase  "plan", "tool", "assess" or "guardrail"
 * @param title  short label, for example the tool name
 * @param detail what happened or what was found
 * @param millis time the step took
 */
public record TraceStep(String phase, String title, String detail, long millis) { }
