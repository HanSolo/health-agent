package com.example.healthmonitor.alerts;

import com.example.healthmonitor.agent.LlmInfo;
import com.example.healthmonitor.model.HealthAlert;
import com.example.healthmonitor.rest.StreamHub;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fires the {@link HealthAlert} event that starts the agent workflow.
 *
 * <p>{@code Event.fire} is synchronous: the whole workflow, including the slow LLM call,
 * runs on the firing thread. So the event is fired from a managed executor thread, and only
 * one analysis runs at a time. A small local model on a laptop is slow, and a second
 * concurrent request would only make both slower.
 */
@ApplicationScoped
public class AlertDispatcher {
    private static final Logger LOG = Logger.getLogger(AlertDispatcher.class.getName());
    public record Status(boolean analyzing, String llm) { }

    @Inject Event<HealthAlert> alerts;
    @Inject StreamHub          hub;
    @Inject LlmInfo            llmInfo;

    @Resource(lookup = "java:comp/DefaultManagedExecutorService")
    ManagedExecutorService executor;

    private final AtomicBoolean busy = new AtomicBoolean();


    /** @return false when an analysis is already running and the alert was not accepted */
    public boolean dispatch(final HealthAlert alert) {
        if (!busy.compareAndSet(false, true)) { return false; }
        publishStatus();
        try {
            executor.execute(() -> run(alert));
        } catch (RuntimeException e) {
            busy.set(false);
            publishStatus();
            throw e;
        }
        return true;
    }

    public boolean isBusy() {
        return busy.get();
    }

    public String statusJson() {
        return hub.toJson(new Status(busy.get(), llmInfo.describe()));
    }

    private void run(final HealthAlert alert) {
        try {
            alerts.fire(alert);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Agent workflow failed for " + alert.kind() + ": " + e, e);
        } finally {
            busy.set(false);
            publishStatus();
        }
    }

    private void publishStatus() {
        hub.publish("status", statusJson());
    }
}
