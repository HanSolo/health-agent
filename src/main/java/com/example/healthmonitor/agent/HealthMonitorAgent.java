package com.example.healthmonitor.agent;

import com.example.healthmonitor.model.Evidence;
import com.example.healthmonitor.model.HealthAlert;
import com.example.healthmonitor.model.HealthAssessment;
import com.example.healthmonitor.model.InvestigationPlan;
import com.example.healthmonitor.model.Proposal;
import com.example.healthmonitor.model.Severity;
import com.example.healthmonitor.model.ToolResult;
import com.example.healthmonitor.model.TraceStep;
import jakarta.ai.agent.Action;
import jakarta.ai.agent.Agent;
import jakarta.ai.agent.Decision;
import jakarta.ai.agent.HandleException;
import jakarta.ai.agent.LLMException;
import jakarta.ai.agent.LargeLanguageModel;
import jakarta.ai.agent.Outcome;
import jakarta.ai.agent.Trigger;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The health monitor agent, written against the Jakarta Agentic AI draft (1.0.0-M1).
 *
 * <p>Workflow, in the order of the {@code order} attributes:
 * <ol>
 *   <li>{@link #onAlert} - trigger: a CDI event carrying a {@link HealthAlert} arrives</li>
 *   <li>{@link #hasEnoughData} - decision: is there enough data to judge?</li>
 *   <li>{@link #plan} - action: the model chooses which evidence tools to run</li>
 *   <li>{@link #investigate} - action: run those tools (plain code, no side effects)</li>
 *   <li>{@link #assess} - action: the model judges the situation from the evidence</li>
 *   <li>{@link #worthReporting} - decision: does the result deserve attention?</li>
 *   <li>{@link #propose} - action: guardrails check what the model proposes to do about it</li>
 *   <li>{@link #record} - outcome: store and publish. A person approves or rejects from the page.</li>
 * </ol>
 * Data moves between the steps by type: {@code HealthAlert} comes from the trigger event, and
 * {@code InvestigationPlan}, {@code Evidence}, {@code HealthAssessment} and {@code Proposal}
 * are the return values of the actions.
 *
 * <p>The agent is workflow scoped, so the fields below belong to one run of the workflow.
 * Nothing in this workflow changes anything outside the application. Acting on a proposal
 * happens later, after approval, in the REST layer.
 */
@Agent(name = "HealthMonitorAgent",
        description = "Watches Mac system metrics, investigates unusual load with evidence tools and proposes a fix that a person approves")
public class HealthMonitorAgent {

    private static final Logger LOG         = Logger.getLogger(HealthMonitorAgent.class.getName());

    /** Fewer samples than this cannot say anything about a trend. */
    private static final int MIN_SAMPLES = 5;

    @Inject
    AssessmentStore store;

    @Inject
    LlmInfo llmInfo;

    @Inject
    EvidenceTools tools;

    @Inject
    Guardrails guardrails;

    private final List<TraceStep> trace = new ArrayList<>();
    private String source = "llm";
    private long   llmMillis;

    @Trigger
    public void onAlert(HealthAlert alert) {
        LOG.info(() -> "Agent triggered: " + alert.kind() + " - " + alert.detail());
    }

    @Decision(order = 10)
    public boolean hasEnoughData(HealthAlert alert) {
        boolean enough = alert.manual() || alert.window().samples() >= MIN_SAMPLES;
        if (!enough) {
            LOG.info("Not enough samples yet, ending the workflow");
        }
        return enough;
    }

    /** The model decides what to look at. If it fails or answers nonsense, every tool runs. */
    @Action(order = 20)
    public InvestigationPlan plan(HealthAlert alert, LargeLanguageModel llm) {
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
        long millis = (System.nanoTime() - started) / 1_000_000;
        llmMillis += millis;
        if (plan == null) {
            plan = new InvestigationPlan(EvidenceTools.ALL, why, false);
        }
        trace.add(new TraceStep("plan", plan.fromLlm() ? "Model chose the tools" : "Default plan",
                String.join(", ", plan.tools()) + ". " + plan.reason(), millis));
        return plan;
    }

    @Action(order = 30)
    public Evidence investigate(HealthAlert alert, InvestigationPlan plan) {
        List<ToolResult> results = new ArrayList<>();
        for (String tool : plan.tools()) {
            ToolResult result = tools.run(tool, alert);
            results.add(result);
            trace.add(new TraceStep("tool", tool, result.summary(), result.millis()));
        }
        return new Evidence(results);
    }

    @Action(order = 40)
    public HealthAssessment assess(HealthAlert alert, Evidence evidence, LargeLanguageModel llm) {
        long             started = System.nanoTime();
        HealthAssessment result  = null;
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
        long millis = (System.nanoTime() - started) / 1_000_000;
        llmMillis += millis;
        if (result == null) {
            source = "rules";
            result = AssessmentSupport.fallback(alert);
            trace.add(new TraceStep("assess", "Rule-based assessment", result.summary(), millis));
        } else {
            source = "llm";
            trace.add(new TraceStep("assess", "Model assessed the evidence", result.severity() + ": " + result.summary(), millis));
        }
        return result;
    }

    @Decision(order = 50)
    public boolean worthReporting(HealthAlert alert, HealthAssessment assessment) {
        return alert.manual() || assessment.severity().atLeast(Severity.MEDIUM);
    }

    /** Plain code on purpose: the model's suggestion is checked, never trusted. */
    @Action(order = 60)
    public Proposal propose(HealthAlert alert, HealthAssessment assessment) {
        Proposal proposal = guardrails.review(alert, assessment.proposed());
        String detail = switch (proposal.status()) {
            case NONE -> "The agent proposes no action.";
            case PENDING -> "Proposal accepted for approval: " + proposal.action()
                    + (proposal.processName() == null ? "" : " on " + proposal.processName())
                    + ". " + proposal.guardrail();
            default -> "Proposal blocked: " + proposal.guardrail();
        };
        trace.add(new TraceStep("guardrail", "Policy check", detail, 0));
        return proposal;
    }

    @Outcome
    public void record(HealthAlert alert, HealthAssessment assessment, Proposal proposal) {
        store.add(alert, assessment, proposal, List.copyOf(trace), source, llmInfo.describe(), llmMillis);
    }

    @HandleException
    public void onFailure(RuntimeException failure, HealthAlert alert) {
        LOG.log(Level.WARNING, "Workflow failed: " + failure, failure);
        store.addError(alert, failure.toString(), llmInfo.describe(), List.copyOf(trace));
        // Rethrowing ends the workflow. Returning normally would let it continue.
        throw failure;
    }
}
