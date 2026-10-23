package com.example.healthmonitor.agent;

import com.example.healthmonitor.model.Evidence;
import com.example.healthmonitor.model.HealthAlert;
import com.example.healthmonitor.model.HealthAssessment;
import com.example.healthmonitor.model.InvestigationPlan;
import com.example.healthmonitor.model.TraceStep;
import jakarta.ai.agent.LLMException;
import jakarta.ai.agent.LargeLanguageModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The two places where the agent asks the language model something, with everything that makes
 * that safe: timing, the trace entry, and a fallback when the model is missing, fails, or
 * answers nonsense. Keeping this out of {@link HealthMonitorAgent} leaves the agent showing only
 * the workflow.
 */
@ApplicationScoped
public class Reasoning {

    private static final Logger LOG = Logger.getLogger(Reasoning.class.getName());

    /** What one run of the workflow has done so far. Created per run by the agent. */
    public static final class RunLog {
        private final List<TraceStep> steps = new ArrayList<>();
        private String source = "llm";
        private long llmMillis;

        public void step(String phase, String title, String detail, long millis) {
            steps.add(new TraceStep(phase, title, detail, millis));
        }

        public List<TraceStep> steps() {
            return List.copyOf(steps);
        }

        /** "llm" when the model answered the assessment, "rules" when the fallback did. */
        public String source() {
            return source;
        }

        public long llmMillis() {
            return llmMillis;
        }
    }

    @Inject
    LlmInfo llmInfo;

    /** The model picks which evidence tools to run. If it cannot, every tool runs. */
    public InvestigationPlan choosePlan(LargeLanguageModel llm, HealthAlert alert, RunLog run) {
        long started = System.nanoTime();
        InvestigationPlan plan = null;
        String why = "No LLM is configured, so every tool runs.";
        if (llmInfo.configured()) {
            try {
                String reply = llm.query(AssessmentSupport.planPrompt(alert));
                LOG.fine(() -> "Plan reply: " + reply);
                plan = AssessmentSupport.parsePlan(reply);
                if (plan == null) {
                    why = "The model's plan was unusable, so every tool runs.";
                    LOG.warning("Unusable plan reply: " + reply);
                }
            } catch (LLMException e) {
                why = "The model could not plan (" + e.getMessage() + "), so every tool runs.";
                LOG.log(Level.WARNING, "LLM call failed while planning: " + e, e);
            }
        }
        long millis = elapsedMillis(started);
        run.llmMillis += millis;
        if (plan == null) {
            plan = new InvestigationPlan(EvidenceTools.ALL, why, false);
        }
        run.step("plan", plan.fromLlm() ? "Model chose the tools" : "Default plan",
                String.join(", ", plan.tools()) + ". " + plan.reason(), millis);
        return plan;
    }

    /** The model judges the situation from the evidence. If it cannot, simple rules do. */
    public HealthAssessment judge(LargeLanguageModel llm, HealthAlert alert, Evidence evidence, RunLog run) {
        long started = System.nanoTime();
        HealthAssessment result = null;
        if (llmInfo.configured()) {
            try {
                String reply = llm.query(AssessmentSupport.assessPrompt(alert, evidence));
                LOG.fine(() -> "Assessment reply: " + reply);
                result = AssessmentSupport.parse(reply);
                if (result == null) {
                    LOG.warning("The LLM reply was not usable JSON, using the rule-based fallback. Reply: " + reply);
                }
            } catch (LLMException e) {
                LOG.log(Level.WARNING, "LLM call failed (is Ollama running and the model pulled?): " + e, e);
            }
        }
        long millis = elapsedMillis(started);
        run.llmMillis += millis;
        if (result == null) {
            run.source = "rules";
            result = AssessmentSupport.fallback(alert);
            run.step("assess", "Rule-based assessment", result.summary(), millis);
        } else {
            run.source = "llm";
            run.step("assess", "Model assessed the evidence", result.severity() + ": " + result.summary(), millis);
        }
        return result;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
