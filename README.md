# Mac Health Agent (Jakarta EE 11, Jakarta Agentic AI 1.0.0-M1, Payara Micro)

Consumes the Swift menu-bar app's WebSocket (ws://127.0.0.1:8787), watches for unusual load,
and lets an agent ask a local Ollama model what is going on. Live page at http://localhost:8080/.

## Run

1. Start SysWatch.app
2. `ollama pull gemma3:4b` and make sure Ollama is running (`ollama run qwen3:27b`) (http://localhost:11434).
3. `mvn clean package`
4. `java -jar payara-micro.jar --nocluster --deploy target/health-agent.war`
5. Open http://localhost:8080/ and click "Analyze now".

Check http://localhost:8080/api/status: `llm` must say `ollama:gemma3:4b`, not `none`.
If it says `none`, the config file was not picked up; pass the keys as system properties instead:
`java -Dpayara.agentic.llm.provider=ollama -Dpayara.agentic.llm.model=gemma3:4b -jar payara-micro.jar --deploy target/health-agent.war`

## Trigger it without "Analyze now"

Lower `alerts.cpu.percent` in `src/main/resources/META-INF/microprofile-config.properties`
(for example to 20), rebuild, and run `yes > /dev/null` in a terminal for about 30 seconds.
Overall CPU is averaged over all cores, so one busy core barely moves it; run several `yes` processes.

## Investigate and approve (demo with the Scenario Simulator)

The real System Monitor is read-only, so the approval step needs the separate Scenario Simulator
app (project `scenario-simulator`, same port 8787, same JSON plus `"source":"simulator"`).

1. Quit the real monitor (both use port 8787), start the Scenario Simulator.
2. For a quick demo set `alerts.window-seconds=10` in `microprofile-config.properties`, rebuild, restart.
3. Pick a scenario in the simulator (runaway CPU, memory leak, disk filling, battery drain, benign build, calm).
4. On the page, open the alert card: the trace shows plan, tool calls, assessment and guardrail check.
5. If the proposal is "Needs your approval", click Approve. The command goes to the simulator, and
   6 seconds later the page adds a verification line (metric before and after).

How it works:

- The agent plans which evidence tools to run (`metric_trend`, `process_history`, `recent_alerts`,
  `runbook_lookup`), runs them, then assesses with that evidence and may propose one action.
- The workflow never acts. `Guardrails` check the proposal in plain Java: allowed action
  (`terminate_process`, `purge_temp_files`), the pid must be in the latest snapshot, and
  `runbook.json` must allow it for this alert kind and process (first match wins, fail closed).
- Approve and Reject are `POST /api/assessments/{id}/approve|reject`; only one request can claim a proposal.
- Against the real monitor an approved command times out after 5 s and is reported as failed.

## Where things are

- `agent/HealthMonitorAgent`: the workflow (Trigger, Decision, plan, investigate, assess, Decision, propose, Outcome)
- `agent/Reasoning`: the two model calls with timing, trace and fallback, so the agent shows only the workflow
- `agent/EvidenceTools`, `agent/Runbook`, `agent/Guardrails`, `src/main/resources/runbook.json`
- `remediation/RemediationExecutor`: sends the approved command and verifies the effect
- `alerts/AnomalyDetector`: threshold rules that decide when to fire the trigger event
- `agent/AssessmentSupport`: prompts, parsing of the model's JSON, rule-based fallback
- `rest/*`: SSE stream (`/api/stream`), `/api/status`, `/api/assessments`, `POST /api/analyze`
- `src/main/webapp/index.html`: the live page
