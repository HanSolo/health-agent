package com.example.healthmonitor.metrics;

import com.example.healthmonitor.model.CommandResult;
import com.example.healthmonitor.model.MetricsSnapshot;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connects to the Swift system monitor's WebSocket and feeds {@link MetricsBuffer}.
 *
 * <p>Starts when the application starts, reconnects automatically when the Mac app is not
 * running or the connection drops, and never blocks the WebSocket callback thread with
 * anything slower than parsing one JSON message.
 */
@ApplicationScoped
public class MetricsClient {
    private static final Logger LOG                      = Logger.getLogger(MetricsClient.class.getName());
    private static final int    SUMMARY_EVERY_N_MESSAGES = 10;

    @Inject
    @ConfigProperty(name = "metrics.ws.uri", defaultValue = "ws://127.0.0.1:8787")
    String uri;

    @Inject
    @ConfigProperty(name = "metrics.ws.reconnect-seconds", defaultValue = "5")
    int reconnectSeconds;

    @Inject MetricsBuffer          buffer;
    @Inject Event<MetricsSnapshot> snapshotEvent;


    @Resource(lookup = "java:comp/DefaultManagedScheduledExecutorService")
    ManagedScheduledExecutorService scheduler;

    private final    Jsonb   jsonb = JsonbBuilder.create();

    // Commands waiting for the monitored machine's answer, by command id.
    private final    Map<String, CompletableFuture<CommandResult>> pending = new ConcurrentHashMap<>();

    private volatile Session session;
    private volatile boolean closing;
    private volatile boolean connected;
    private volatile Instant lastMessageAt;
    private volatile long    received;
    private volatile long    parseErrors;

    void onStart(@Observes @Initialized(ApplicationScoped.class) final Object event) {
        LOG.info(() -> "Metrics client starting, target " + uri);
        scheduler.execute(this::connect);
    }

    @PreDestroy
    void shutdown() {
        closing = true;
        final Session current = session;
        if (current != null) {
            try {
                current.close();
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error while closing WebSocket session", e);
            }
        }
        try {
            jsonb.close();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error while closing JSON-B", e);
        }
    }


    // ******************** State for the status endpoint  ********************
    public boolean isConnected() {
        return connected;
    }

    public String uri() {
        return uri;
    }

    public Instant lastMessageAt() {
        return lastMessageAt;
    }

    public long received() {
        return received;
    }

    public long parseErrors() {
        return parseErrors;
    }


    // ******************** Connection handling *******************************
    private void connect() {
        if (closing) { return; }
        try {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
                    .configurator(new ClientEndpointConfig.Configurator() {
                        @Override
                        public void beforeRequest(Map<String, List<String>> headers) {
                            // The Swift server rejects any request that carries an Origin header (browser protection). Non-browser clients do not need one.
                            headers.keySet().removeIf(name -> name.equalsIgnoreCase("Origin"));
                        }
                    })
                    .build();
            session = container.connectToServer(new MetricsEndpoint(), config, URI.create(uri));
        } catch (Exception e) {
            LOG.warning(() -> "Could not connect to " + uri + ": " + e + " (is the Mac app running? If the message mentions HTTP 400, the Mac app rejected an Origin header, see README)");
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!closing) {
            scheduler.schedule(this::connect, reconnectSeconds, TimeUnit.SECONDS);
        }
    }

    private void onMessage(final String json) {
        try {
            // Route by message type first: only real snapshots may reach the buffer.
            final String type;
            try (var reader = Json.createReader(new java.io.StringReader(json))) {
                type = reader.readObject().getString("type", "snapshot");
            }
            if ("commandResult".equals(type)) {
                completeCommand(json);
                return;
            }
            if (!"snapshot".equals(type)) {
                LOG.fine(() -> "Ignoring message of type " + type);
                return;
            }
            final MetricsSnapshot snapshot = jsonb.fromJson(json, MetricsSnapshot.class);
            if (snapshot.timestamp() == null) {
                parseErrors++;
                LOG.warning("Ignoring a snapshot without a timestamp");
                return;
            }
            buffer.add(snapshot);
            lastMessageAt = Instant.now();
            long count = ++received;
            if (count % SUMMARY_EVERY_N_MESSAGES == 1) {
                LOG.info(() -> summary(snapshot));
            }
            publish(snapshot);
        } catch (Exception e) {
            parseErrors++;
            LOG.log(Level.WARNING, "Could not parse metrics message: " + e, e);
        }
    }

    private void completeCommand(final String json) {
        final CommandResult                    result  = jsonb.fromJson(json, CommandResult.class);
        final CompletableFuture<CommandResult> waiting = pending.remove(result.id());
        if (waiting != null) {
            waiting.complete(result);
        }
    }

    /**
     * Sends a command to the monitored machine and returns a future for its answer. The real
     * System Monitor app ignores commands, so the future then fails with a timeout. Only the
     * Scenario Simulator answers.
     *
     * @throws IllegalStateException when there is no open connection
     */
    public CompletableFuture<CommandResult> sendCommand(final String action, final Integer pid, final long timeoutSeconds) {
        Session current = session;
        if (!connected || current == null || !current.isOpen()) {
            throw new IllegalStateException("Not connected to the monitored machine.");
        }
        String id = UUID.randomUUID().toString();
        JsonObjectBuilder command = Json.createObjectBuilder()
                                        .add("type", "command")
                                        .add("id", id)
                                        .add("action", action);
        if (pid != null) {
            command.add("pid", pid);
        }

        CompletableFuture<CommandResult> answer = new CompletableFuture<CommandResult>().orTimeout(timeoutSeconds, TimeUnit.SECONDS);
        answer.whenComplete((result, error) -> pending.remove(id));
        pending.put(id, answer);
        try {
            synchronized (current) {
                current.getBasicRemote().sendText(command.build().toString());
            }
        } catch (IOException e) {
            answer.completeExceptionally(e);
        }
        return answer;
    }

    /** Observers (anomaly detector, live page) must never break the receive loop. */
    private void publish(final MetricsSnapshot snapshot) {
        try {
            snapshotEvent.fire(snapshot);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "A snapshot observer failed: " + e, e);
        }
    }

    private static String summary(final MetricsSnapshot snapshot) {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "cpu %.1f%%  mem %.1f%%  net %.1f%%  disk %.1f%%", snapshot.cpu().percent(), snapshot.memory().percent(), snapshot.network().percent(), snapshot.disk().percent()));
        if (snapshot.battery() != null) {
            text.append(String.format(Locale.ROOT, "  battery %.0f%%%s", snapshot.battery().percent(), snapshot.battery().charging() ? " (charging)" : ""));
        }
        if (snapshot.topProcesses() != null && !snapshot.topProcesses().cpu().isEmpty()) {
            var top = snapshot.topProcesses().cpu().get(0);
            text.append(String.format(Locale.ROOT, "  top: %s %.0f%%", top.name(), top.cpuPercent()));
        }
        return text.toString();
    }


    // ******************** Internal Classes **********************************
    private final class MetricsEndpoint extends Endpoint {

        @Override
        public void onOpen(final Session openedSession, final EndpointConfig config) {
            connected = true;
            LOG.info(() -> "Connected to " + uri);
            openedSession.addMessageHandler(String.class, (MessageHandler.Whole<String>) MetricsClient.this::onMessage);
        }

        @Override
        public void onClose(final Session closedSession, final CloseReason reason) {
            connected = false;
            LOG.info(() -> "Connection closed: " + reason);
            scheduleReconnect();
        }

        @Override
        public void onError(final Session erroredSession, final Throwable error) {
            LOG.log(Level.WARNING, "WebSocket error: " + error, error);
        }
    }
}
