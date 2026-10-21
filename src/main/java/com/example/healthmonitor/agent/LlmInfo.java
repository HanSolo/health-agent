package com.example.healthmonitor.agent;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.logging.Logger;

/**
 * Reads the same MicroProfile Config keys as Payara's agentic runtime, so the page and the
 * logs can say which model is (supposed to be) answering.
 *
 * <p>With provider {@code none} Payara injects a no-op model that returns a fixed answer
 * without any error, which would look like the LLM is working. Hence the explicit check.
 */
@ApplicationScoped
public class LlmInfo {

    private static final Logger LOG = Logger.getLogger(LlmInfo.class.getName());

    @Inject
    @ConfigProperty(name = "payara.agentic.llm.provider", defaultValue = "none")
    String provider;

    @Inject
    @ConfigProperty(name = "payara.agentic.llm.model", defaultValue = "")
    String model;

    @PostConstruct
    void check() {
        if (!configured()) {
            LOG.warning("payara.agentic.llm.provider is 'none' or unset: the agent will use its rule-based fallback instead of an LLM. Set payara.agentic.llm.provider=ollama.");
        } else {
            LOG.info(() -> "LLM configured: " + describe());
        }
    }

    public boolean configured() {
        return provider != null && !provider.isBlank() && !"none".equalsIgnoreCase(provider);
    }

    public String describe() {
        if (!configured()) { return "none (rule-based fallback)"; }
        return model == null || model.isBlank() ? provider : provider + ":" + model;
    }
}
