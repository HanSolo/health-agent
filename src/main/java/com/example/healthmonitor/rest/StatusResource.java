package com.example.healthmonitor.rest;

import com.example.healthmonitor.agent.LlmInfo;
import com.example.healthmonitor.alerts.AlertDispatcher;
import com.example.healthmonitor.metrics.MetricsBuffer;
import com.example.healthmonitor.metrics.MetricsClient;
import com.example.healthmonitor.model.MetricsSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;

/** Connection state, LLM setup and the newest snapshot, for checking that everything is wired up. */
@Path("status")
@ApplicationScoped
public class StatusResource {
    public record Status(boolean connected, String uri, long received, long parseErrors, Instant lastMessageAt, int buffered, String llm, boolean analyzing, MetricsSnapshot latest) { }

    @Inject MetricsClient   client;
    @Inject MetricsBuffer   buffer;
    @Inject LlmInfo         llmInfo;
    @Inject AlertDispatcher dispatcher;


    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Status status() {
        return new Status(client.isConnected(), client.uri(), client.received(), client.parseErrors(), client.lastMessageAt(), buffer.size(), llmInfo.describe(), dispatcher.isBusy(), buffer.latest().orElse(null));
    }
}
