package com.example.healthmonitor.model;

/** The monitored machine's answer to a command (sent by the scenario simulator). */
public record CommandResult(String type, String id, String action, boolean ok, String message) { }
