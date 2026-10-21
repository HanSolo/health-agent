package com.example.healthmonitor.rest;

import com.example.healthmonitor.agent.AssessmentStore;
import com.example.healthmonitor.alerts.AlertDispatcher;
import com.example.healthmonitor.metrics.MetricsBuffer;
import com.example.healthmonitor.model.AssessmentRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.List;

/**
 * The live page's single connection. Events: {@code metrics} (about once a second),
 * {@code assessment} (one per finished analysis) and {@code status}.
 */
@Path("stream")
@ApplicationScoped
public class StreamResource {

    @Inject StreamHub       hub;
    @Inject MetricsBuffer   buffer;
    @Inject AssessmentStore store;
    @Inject AlertDispatcher dispatcher;


    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(@Context final SseEventSink sink, @Context final Sse sse) {
        hub.init(sse);

        // Current state first, so a page opened (or reconnected) mid-session is not empty.
        sink.send(hub.event("status", dispatcher.statusJson()));
        buffer.latest().ifPresent(latest -> sink.send(hub.event("metrics", hub.toJson(latest))));
        List<AssessmentRecord> past = store.list();
        for (int i = past.size() - 1; i >= 0; i--) {
            sink.send(hub.event("assessment", hub.toJson(past.get(i))));
        }

        hub.register(sink);
    }
}
