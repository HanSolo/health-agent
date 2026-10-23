package com.example.healthmonitor.agent;

import com.example.healthmonitor.agent.Reasoning.RunLog;
import com.example.healthmonitor.model.Evidence;
import com.example.healthmonitor.model.HealthAlert;
import com.example.healthmonitor.model.HealthAssessment;
import com.example.healthmonitor.model.InvestigationPlan;
import com.example.healthmonitor.model.Proposal;
import com.example.healthmonitor.model.Severity;
import com.example.healthmonitor.model.ToolResult;
import jakarta.ai.agent.Action;
import jakarta.ai.agent.Agent;
import jakarta.ai.agent.Decision;
import jakarta.ai.agent.HandleException;
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
 * <p>Read the methods top to bottom, in the order of their {@code order} attributes:
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
 * <p>Nothing in this workflow changes anything outside the application. Acting on a proposal
 * happens later, after approval, in the remediation executor. The model calls, their fallbacks
 * and their timing live in {@link Reasoning}.
 */
@Agent(name = "HealthMonitorAgent",
        description = "Watches Mac system metrics, investigates unusual load with evidence tools and proposes a fix that a person approves")
public class HealthMonitorAgent {

    private static final Logger LOG         = Logger.getLogger(HealthMonitorAgent.class.getName());

    /** Fewer samples than this cannot say anything about a trend. */
    private static final int MIN_SAMPLES = 5;

    @Inject
    Reasoning reasoning;

    @Inject
    EvidenceTools tools;

    @Inject
    Guardrails guardrails;

    @Inject
    AssessmentStore store;

    @Inject
    LlmInfo llmInfo;

    /** What this run did, shown on the page as the evidence trace. The agent is workflow scoped. */
    private final RunLog run = new RunLog();

    @Trigger
    public void onAlert(HealthAlert alert) {
        LOG.info(() -> "Agent triggered: " + alert.kind() + " - " + alert.detail());
    }

    @Decision(order = 10)
    public boolean hasEnoughData(HealthAlert alert) {
        return alert.manual() || alert.window().samples() >= MIN_SAMPLES;
    }

    @Action(order = 20)
    public InvestigationPlan plan(HealthAlert alert, LargeLanguageModel llm) {
        return reasoning.choosePlan(llm, alert, run);
    }

    @Action(order = 30)
    public Evidence investigate(HealthAlert alert, InvestigationPlan plan) {
        List<ToolResult> results = new ArrayList<>();
        for (String tool : plan.tools()) {
            ToolResult result = tools.run(tool, alert);
            results.add(result);
            run.step("tool", tool, result.summary(), result.millis());
        }
        return new Evidence(results);
    }

    @Action(order = 40)
    public HealthAssessment assess(HealthAlert alert, Evidence evidence, LargeLanguageModel llm) {
        return reasoning.judge(llm, alert, evidence, run);
    }

    @Decision(order = 50)
    public boolean worthReporting(HealthAlert alert, HealthAssessment assessment) {
        return alert.manual() || assessment.severity().atLeast(Severity.MEDIUM);
    }

    /** Plain code on purpose: the model's suggestion is checked, never trusted. */
    @Action(order = 60)
    public Proposal propose(HealthAlert alert, HealthAssessment assessment) {
        Proposal proposal = guardrails.review(alert, assessment.proposed());
        run.step("guardrail", "Policy check", proposal.describe(), 0);
        return proposal;
    }

    @Outcome
    public void record(HealthAlert alert, HealthAssessment assessment, Proposal proposal) {
        store.add(alert, assessment, proposal, run.steps(), run.source(), llmInfo.describe(), run.llmMillis());
    }

    @HandleException
    public void onFailure(RuntimeException failure, HealthAlert alert) {
        LOG.log(Level.WARNING, "Workflow failed: " + failure, failure);
        store.addError(alert, failure.toString(), llmInfo.describe(), run.steps());
        // Rethrowing ends the workflow. Returning normally would let it continue.
        throw failure;
    }
}
