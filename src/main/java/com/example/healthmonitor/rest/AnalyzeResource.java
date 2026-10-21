package com.example.healthmonitor.rest;

import com.example.healthmonitor.alerts.AlertDispatcher;
import com.example.healthmonitor.alerts.AlertFactory;
import com.example.healthmonitor.metrics.MetricsClient;
import com.example.healthmonitor.model.AlertKind;
import com.example.healthmonitor.model.HealthAlert;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** "Analyze now": starts the agent workflow on demand, without waiting for a threshold. */
@Path("analyze")
@ApplicationScoped
public class AnalyzeResource {
    public record Message(String message) { }

    @Inject MetricsClient   client;
    @Inject AlertFactory    factory;
    @Inject AlertDispatcher dispatcher;


    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response analyze() {
        // The buffer keeps old snapshots after the Mac app stops, so check the live connection too.
        Instant last = client.lastMessageAt();
        boolean live = client.isConnected() && last != null && Duration.between(last, Instant.now()).toSeconds() < 10;
        if (!live) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(new Message("The Mac app is not sending metrics.")).build();
        }
        Optional<HealthAlert> alert = factory.create(AlertKind.MANUAL, "Health check requested from the live page");
        if (alert.isEmpty()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(new Message("No metrics received yet. Is the Mac app running?")).build();
        }
        if (!dispatcher.dispatch(alert.get())) {
            return Response.status(Response.Status.CONFLICT).entity(new Message("An analysis is already running.")).build();
        }
        return Response.accepted(new Message("Analysis started.")).build();
    }
}
