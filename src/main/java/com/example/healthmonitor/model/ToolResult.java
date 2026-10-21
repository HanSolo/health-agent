package com.example.healthmonitor.model;

/** What one evidence tool found, as plain text that goes into the next prompt. */
public record ToolResult(String tool, String summary, long millis) { }
