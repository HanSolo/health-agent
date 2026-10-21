package com.example.healthmonitor.rest;

import com.example.healthmonitor.model.MetricsSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/** Forwards every snapshot to the live pages. */
@ApplicationScoped
public class MetricsPublisher {

    @Inject StreamHub hub;


    void onSnapshot(@Observes final MetricsSnapshot snapshot) {
        if (hub.active()) {
            hub.publish("metrics", hub.toJson(snapshot));
        }
    }
}
