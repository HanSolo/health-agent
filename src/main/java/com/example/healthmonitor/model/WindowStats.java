package com.example.healthmonitor.model;

/**
 * Summary of the recent snapshots around an alert. All percentages are 0 to 100.
 * Battery values are null on Macs without a battery.
 */
public record WindowStats(int seconds, int samples, double cpuAvg, double cpuMax, double memoryAvg, double memoryMax, double networkAvg, double networkMax, double diskAvg, double diskMax, Double batteryStartPercent, Double batteryEndPercent) { }
