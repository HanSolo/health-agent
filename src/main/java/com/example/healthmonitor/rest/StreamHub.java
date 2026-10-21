package com.example.healthmonitor.rest;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;

/**
 * Fan-out of server-sent events to every open live page.
 *
 * <p>{@link Sse} is only handed to REST resource methods, so the hub is initialised by the
 * first client that connects. Until then there is nobody to publish to.
 */
@ApplicationScoped
public class StreamHub {
    private final    Jsonb          jsonb = JsonbBuilder.create();
    private volatile Sse            sse;
    private volatile SseBroadcaster broadcaster;


    public synchronized void init(final Sse sse) {
        if (this.sse == null) {
            this.sse         = sse;
            this.broadcaster = sse.newBroadcaster();
        }
    }

    public void register(SseEventSink sink) {
        broadcaster.register(sink);
    }

    /** True once at least one page has connected; lets callers skip building payloads nobody reads. */
    public boolean active() {
        return broadcaster != null;
    }

    public OutboundSseEvent event(final String name, final String jsonData) {
        return sse.newEventBuilder().name(name).data(jsonData).build();
    }

    public void publish(String name, String jsonData) {
        SseBroadcaster current = broadcaster;
        if (current != null) {
            current.broadcast(event(name, jsonData));
        }
    }

    public String toJson(final Object value) { return jsonb.toJson(value); }

    @PreDestroy
    void shutdown() {
        SseBroadcaster current = broadcaster;
        if (current != null) { current.close(); }
        try {
            jsonb.close();
        } catch (Exception ignored) {
            // Shutting down anyway.
        }
    }
}
