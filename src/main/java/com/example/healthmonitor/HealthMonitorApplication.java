package com.example.healthmonitor;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** Activates Jakarta REST under /api. */
@ApplicationPath("/api")
public class HealthMonitorApplication extends Application { }
