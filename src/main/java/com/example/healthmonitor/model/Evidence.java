package com.example.healthmonitor.model;

import java.util.List;

/** The results of the investigation step. Passed to the assessment step by type. */
public record Evidence(List<ToolResult> results) { }
