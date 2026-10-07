import type { ExtensionAPI, ToolDefinition } from "@mariozechner/pi-coding-agent";
import { appendFileSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { IndexActBridge } from "./bridge.ts";
import { PI_RUNTIME } from "./runtime.ts";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

export async function registerIndexAct(pi: ExtensionAPI, config: any, artifacts: string) {
  const runtimeRoot = process.env.INDEXACT_RUNTIME_ROOT ?? join(PI_RUNTIME, "packages/coding-agent");
  const { SettingsManager } = await import(pathToFileURL(join(runtimeRoot, "dist/core/settings-manager.js")).href);
  const { flushRawStdout } = await import(pathToFileURL(join(runtimeRoot, "dist/core/output-guard.js")).href);
  const bridge = new IndexActBridge(config.python, join(ROOT, "indexact/interface/bridge.py"), join(artifacts, "bridge-config.json"), artifacts);
  const ready = await bridge.ready;
  writeFileSync(join(artifacts, "tools.json"), JSON.stringify(ready, null, 2));
  const maxTurns = config.max_turns;
  if (!Number.isSafeInteger(maxTurns) || maxTurns <= 0) throw new Error("max_turns must be a positive integer");
  let completedTurns = 0;
  const origin = performance.now();
  const record = (kind: string, extra: object = {}) => appendFileSync(join(artifacts, "policy_events.jsonl"),
    JSON.stringify({ kind, offset_seconds: (performance.now() - origin) / 1000, ...extra }) + "\n");
  let submitted = false;
  let turnToolNames: string[] = [];
  let queue: Promise<unknown> = Promise.resolve();
  let admittedTurns = 0;
  let logicalInput = 0;
  let outputTokens = 0;
  const budgets = config.interface?.base_config.runtime;
  let budgetReason: string | undefined;
  let stopping: Promise<never> | undefined;
  const stopAtTurnLimit = (): Promise<never> => stopping ??= (async () => {
    const limit = { max_turns: maxTurns, completed_turns: completedTurns, reason: budgetReason ?? "max_turns" };
    record("max_turns", limit);
    writeFileSync(join(artifacts, "turn_limit.json"), JSON.stringify(limit));
    // Pi's lifecycle events are asynchronous. Admission below also waits here,
    // so closing/flushing cannot race a model request for turn N+1.
    try { await bridge.close(); await flushRawStdout(); }
    finally { process.exit(0); }
  })();

  pi.on("session_start", async (_event, ctx) => {
    const settings = SettingsManager.create(ctx.cwd, process.env.PI_CODING_AGENT_DIR);
    if (settings.getCompactionSettings().enabled)
      throw new Error("IndexAct requires automatic context compaction to be disabled");
    writeFileSync(join(artifacts, "runtime_settings.json"), JSON.stringify({
      pi_version: config.pi_runtime?.version, runtime: config.pi_runtime,
      compaction: settings.getCompactionSettings(),
      retry: settings.getRetrySettings(), provider_retry: settings.getProviderRetrySettings?.(),
      model: ctx.model ? { id: ctx.model.id, provider: ctx.model.provider, api: ctx.model.api,
        contextWindow: ctx.model.contextWindow, maxTokens: ctx.model.maxTokens } : null,
      indexact_tools: ready.tools.map(x => x.name), indexact_execution: "sequential",
      turn_policy: { max_turns: maxTurns, counting: "completed assistant turns including tool batch" },
      safety_timeout_seconds: config.timeout_seconds,
      profile: "indexact", context_management: "none",
      indexact_source_sha256: config.interface?.source_sha256,
      observation_mode: config.interface?.observation_mode ?? "exact",
      capability_mode: config.interface?.capability_mode ?? "full",
    }, null, 2));
  });
  let finishing: Promise<never> | undefined;
  const finishSubmission = (): Promise<never> => finishing ??= (async () => {
    try { await bridge.close(); await flushRawStdout(); }
    finally { process.exit(0); }
  })();

  pi.on("before_agent_start", async event => {
    const systemPrompt = config.interface.prompt_text;
    writeFileSync(join(artifacts, "system_prompt.txt"), systemPrompt);
    writeFileSync(join(artifacts, "user_prompt.txt"), event.prompt);
    return { systemPrompt };
  });
  pi.on("turn_end", async () => {
    // One completed assistant response + its complete tool batch, not one tool call.
    // Keep this counter across Pi retries/compaction; agent_start may repeat.
    completedTurns++;
    if (submitted) { await finishSubmission(); return; }
    if (completedTurns >= maxTurns || budgetReason) {
      // Pinned Pi print mode has no extension stop-after-turn hook. Close only
      // our bridge and flush completed events before exiting, never request turn N+1.
      await stopAtTurnLimit();
    }
  });
  pi.on("message_end", async event => {
    if (event.message.role === "assistant") {
      turnToolNames = event.message.content.filter(x => x.type === "toolCall").map(x => x.name);
      const usage = event.message.usage;
      logicalInput += (usage.input ?? 0) + (usage.cacheRead ?? 0) + (usage.cacheWrite ?? 0);
      outputTokens += usage.output ?? 0;
      if (budgets.max_input_tokens != null && logicalInput >= budgets.max_input_tokens) budgetReason = "max_input_tokens";
      if (budgets.max_total_output_tokens != null && outputTokens >= budgets.max_total_output_tokens) budgetReason = "max_total_output_tokens";
    }
  });
  pi.on("context", async event => {
    if (submitted) await finishSubmission();
    if (admittedTurns >= maxTurns || budgetReason) await stopAtTurnLimit();
    admittedTurns++;
    record("prepared_context", { message_count: event.messages.length,
      turn: admittedTurns, message_bytes: Buffer.byteLength(JSON.stringify(event.messages)) });
  });
  pi.on("before_provider_request", async event => {
    record("provider_request", { payload_bytes: Buffer.byteLength(JSON.stringify(event.payload)) });
    // Save actual model input/options, never transport authentication headers.
    const payload = event.payload as Record<string, unknown>;
    const allowed = ["model", "messages", "input", "instructions", "tools", "tool_choice",
      "parallel_tool_calls", "max_tokens", "max_completion_tokens", "max_output_tokens",
      "temperature", "reasoning", "reasoning_effort", "store"];
    const visible = Object.fromEntries(allowed.filter(k => k in payload).map(k => [k, payload[k]]));
    let line = JSON.stringify({ offset_seconds: (performance.now() - origin) / 1000, payload: visible });
    if (process.env.OPENAI_API_KEY) line = line.split(process.env.OPENAI_API_KEY).join("[REDACTED]");
    appendFileSync(join(artifacts, "provider_requests.jsonl"), line + "\n");
  });
  pi.on("tool_result", async event => {
    if ((event.details as any)?.indexact_status === "error") return { isError: true };
  });
  pi.on("tool_call", async () => {
    if (submitted) return { block: true, reason: "Answer already submitted; episode ended." };
    if (turnToolNames.includes("submit_answer") && turnToolNames.length !== 1)
      return { block: true, reason: "submit_answer must be the only tool call in its turn." };
    if (turnToolNames.length > budgets.max_tool_calls_per_turn)
      return { block: true, reason: `Tool-call batch exceeds max_tool_calls_per_turn=${budgets.max_tool_calls_per_turn}; no calls executed.` };
  });
  for (const spec of ready.tools) {
    pi.registerTool({
      name: spec.name, label: spec.name, description: spec.description,
      promptSnippet: spec.description,
      parameters: spec.parameters as ToolDefinition["parameters"],
      async execute(callId, args, signal) {
        const action = queue.then(async () => {
          if (signal?.aborted) throw new Error("Tool aborted before dispatch; no operation executed.");
          // Recheck after waiting: Pi can request tools concurrently, IndexAct semantics stay sequential.
          if (submitted) throw new Error("Answer already submitted.");
          const result = await bridge.execute(spec.name, args, callId);
          if (result.submitted) {
            submitted = true;
            record("submitted_answer");
          }
          return { content: [{ type: "text" as const, text: JSON.stringify(result.observation) }],
            details: { indexact_status: result.observation.status, submitted: result.submitted },
            terminate: result.submitted };
        });
        queue = action.catch(() => undefined);
        return await action;
      },
    });
  }
  pi.on("session_shutdown", async () => { await bridge.close(); });
  return { bridge };
}

export default async function (pi: ExtensionAPI) {
  const artifacts = process.env.INDEXACT_ARTIFACTS;
  if (!artifacts) throw new Error("Launch with npm run run -- --config ...; INDEXACT_ARTIFACTS missing");
  try {
    const config = JSON.parse(readFileSync(join(artifacts, "bridge-config.json"), "utf8"));
    await registerIndexAct(pi, config, artifacts);
  } catch (error) {
    writeFileSync(join(artifacts, "startup_error.json"), JSON.stringify({ error: String(error) }));
    console.error(String(error));
    // Pi otherwise permits extension load errors and could call a paid model with no tools.
    process.exit(1);
  }
}
