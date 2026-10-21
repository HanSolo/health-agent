package com.example.healthmonitor.model;

/** How serious the agent judges a situation. Declaration order matters: LOW is the mildest. */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH;

    public boolean atLeast(Severity other) {
        return compareTo(other) >= 0;
    }
}
