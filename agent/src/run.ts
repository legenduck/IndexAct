import { spawn, execFileSync } from "node:child_process";
import { createInterface } from "node:readline";
import { createHash } from "node:crypto";
import { appendFileSync, chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { homedir, tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { PI_RUNTIME, runtimeIdentity } from "./runtime.ts";

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
export type Task = { task_id: string; question: string };
export type Config = {
  model: string; thinking: string; max_turns: number; timeout_seconds: number | null;
  python: string; service: Record<string, unknown>; auth_dir?: string; models_file?: string;
  profile?: "indexact";
  interface?: any;
  repository_root?: string; interface_config_path?: string;
  question_template?: string;
  question_guidance_path?: string;
  question_verbatim?: boolean;
  pi_runtime?: ReturnType<typeof runtimeIdentity>;
  // Offline evaluation only: never used in the agent prompt or model request.
  judge?: Record<string, unknown>;
};
const hash = (value: string | Buffer) => createHash("sha256").update(value).digest("hex");
const json = (path: string, value: unknown) => writeFileSync(path, JSON.stringify(value, null, 2) + "\n");

export function loadConfig(path: string): Config {
  const raw = JSON.parse(readFileSync(path, "utf8"));
  const allowed = new Set(["model", "thinking", "max_turns", "timeout_seconds", "python", "service", "auth_dir", "models_file", "profile", "repository_root", "interface_config_path", "interface", "question_template", "question_verbatim", "question_guidance_path", "pi_runtime", "judge"]);
  for (const key of Object.keys(raw)) if (!allowed.has(key)) throw new Error(`Unknown config key: ${key}`);
  const config = { profile: "indexact", thinking: "medium", max_turns: 300, timeout_seconds: null, ...raw,
    python: raw.python ? resolve(dirname(path), raw.python) : join(ROOT, ".venv/bin/python") };
  if (config.profile !== undefined && config.profile !== "indexact") throw new Error("Unknown IndexAct profile");
  if (config.profile === "indexact") {
    config.pi_runtime = runtimeIdentity();
    const repo = ROOT;
    if (raw.repository_root && resolve(dirname(path), raw.repository_root) !== ROOT) throw new Error("IndexAct uses its local interface packages, not another repository");
    const sourceConfig = raw.interface_config_path ? resolve(dirname(path), raw.interface_config_path) : join(ROOT, "configs/interface.yaml");
    const interfaceInfo = JSON.parse(execFileSync(config.python, [join(ROOT, "indexact/interface/profile.py"), "--repo", repo, "--config", sourceConfig], {encoding: "utf8", maxBuffer: 4 * 1024 * 1024}));
    config.interface = interfaceInfo;
    config.repository_root = repo; config.interface_config_path = sourceConfig;
    config.service = raw.service ?? config.interface.base_config.service;
    config.thinking = raw.thinking ?? "high";
    config.max_turns = raw.max_turns ?? config.interface.base_config.runtime.max_turns;
    config.timeout_seconds = raw.timeout_seconds === undefined ? config.interface.base_config.runtime.max_wall_clock_seconds : raw.timeout_seconds;
    if (raw.question_guidance_path) {
      const guidance = readFileSync(resolve(dirname(path), raw.question_guidance_path), "utf8").trimEnd();
      config.question_template = "Answer the following question. The answer is contained in the indexed corpus. **Do Not use web search!**\n\n" + guidance + "\n\nQUESTION:\n{query}\n";
    }
  }
  if (typeof config.model !== "string" || !config.model.includes("/")) throw new Error("model must be provider/model");
  if (!["off", "minimal", "low", "medium", "high", "xhigh"].includes(config.thinking)) throw new Error("Invalid thinking level");
  validateBudgets(config);
  if (config.service?.kind !== undefined) throw new Error("Only the HTTP index service is supported");
  if (!/^snap-[0-9a-f]{64}$/.test(config.service?.snapshot_id ?? "")) throw new Error("Invalid service.snapshot_id");
  const url = new URL(config.service.endpoint);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password) throw new Error("Service endpoint must be HTTP(S), without credentials");
  if (raw.models_file) config.models_file = resolve(dirname(path), raw.models_file);
  if (raw.auth_dir) config.auth_dir = resolve(dirname(path), raw.auth_dir);
  return config;
}

function validateBudgets(config: Config): void {
  if (!Number.isSafeInteger(config.max_turns) || config.max_turns <= 0)
    throw new Error("max_turns must be a positive integer");
  if (config.timeout_seconds !== null && (!Number.isFinite(config.timeout_seconds) || config.timeout_seconds <= 0))
    throw new Error("timeout_seconds must be positive or null (disabled)");
}

export function loadTasks(path: string): Task[] {
  const tasks = readFileSync(path, "utf8").split(/\r?\n/).filter(x => x.trim()).map((line, i) => {
    if (path.endsWith(".tsv")) {
      const tab = line.indexOf("\t");
      if (tab < 1) throw new Error(`Invalid TSV at line ${i + 1}`);
      return { task_id: line.slice(0, tab), question: line.slice(tab + 1) };
    }
    const raw = JSON.parse(line);
    return { task_id: String(raw.task_id ?? raw.query_id ?? raw.id ?? i), question: raw.question ?? raw.query };
  });
  const ids = new Set<string>();
  for (const task of tasks) {
    if (!task.task_id || typeof task.question !== "string" || !task.question.trim()) throw new Error("Every task needs an id and nonempty question");
    if (ids.has(task.task_id)) throw new Error(`Duplicate task_id: ${task.task_id}`);
    ids.add(task.task_id);
  }
  return tasks;
}

export function prepareAgentDir(config: Config): string {
  const dir = mkdtempSync(join(tmpdir(), "indexact-auth-"));
  try {
  const source = config.auth_dir ?? process.env.PI_CODING_AGENT_DIR ?? join(homedir(), ".pi/agent");
  // Isolate each run: inherit credentials, but only explicitly configured model registrations.
  for (const name of ["auth.json", "oauth.json"]) {
    const file = join(source, name);
    if (existsSync(file)) { copyFileSync(file, join(dir, name)); chmodSync(join(dir, name), 0o600); }
  }
  if (config.models_file) {
    if (!existsSync(config.models_file)) throw new Error(`models_file not found: ${config.models_file}`);
    copyFileSync(config.models_file, join(dir, "models.json"));
    chmodSync(join(dir, "models.json"), 0o600);
  }
  const retry = config.interface.base_config.retry;
  json(join(dir, "settings.json"), { compaction: { enabled: false },
    retry: { enabled: true, maxRetries: retry.max_retries ?? 1,
      baseDelayMs: retry.initial_retry_delay_seconds * 1000, maxDelayMs: retry.max_retry_delay_seconds * 1000 } });
  return dir;
  } catch (error) { rmSync(dir, { recursive: true }); throw error; }
}

export async function runTask(config: Config, task: Task, artifacts: string, testExtension?: string, signal?: AbortSignal) {
  signal?.throwIfAborted();
  validateBudgets(config);
  mkdirSync(artifacts, { recursive: false });
  for (const name of ["raw-events.jsonl", "conversation.jsonl", "model_calls.jsonl", "tool_calls.jsonl",
    "evidence.jsonl", "operations.jsonl", "timings.jsonl", "service_http_round_trips.jsonl",
    "policy_events.jsonl", "context_events.jsonl", "provider_requests.jsonl", "stderr.log", "bridge.stderr.log"])
    writeFileSync(join(artifacts, name), "", { flag: "wx" });
  json(join(artifacts, "task.json"), task);
  json(join(artifacts, "bridge-config.json"), { ...config, auth_dir: undefined, models_file: undefined, task });
  const agentDir = prepareAgentDir(config);
  const isolatedCwd = mkdtempSync(join(tmpdir(), "indexact-cwd-"));
  const runtimeRoot = join(PI_RUNTIME, "packages/coding-agent");
  const args = [join(runtimeRoot, "dist/cli.js"),
    "--no-tools", "--no-session", "--no-skills", "--no-extensions", "--no-prompt-templates", "--no-themes",
    "-e", join(ROOT, "agent/src/extension.ts")];
  if (testExtension) args.push("-e", testExtension);
  const prompt = config.question_verbatim ? task.question
    : (config.question_template ?? readFileSync(join(ROOT, "prompts/question.md"), "utf8")).replace("{query}", () => task.question);
  args.push("--mode", "json", "--model", config.model, "--thinking", config.thinking, prompt);
  const started = performance.now();
  let timedOut = false;
  let interrupted = false;
  let exitSignal: NodeJS.Signals | null = null;
  let turns = 0;
  let toolCalls = 0;
  let lastModelStart: number | undefined;
  const usage = { input_uncached: 0, input_cached: 0, cache_write: 0, logical_input: 0, output: 0, reported_cost_usd: 0, billing_cost_usd: null };
  let observedUsage = false;
  const child = spawn(process.execPath, args, { cwd: isolatedCwd, detached: true, stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, PI_CODING_AGENT_DIR: agentDir, INDEXACT_ARTIFACTS: artifacts,
      INDEXACT_RUNTIME_ROOT: runtimeRoot,
      INDEXACT_AI_MODULE: join(PI_RUNTIME, "packages/ai/dist/index.js"),
      INDEXACT_FEATURE_MODE: "1",
      PI_OFFLINE: "1", NO_COLOR: "1" } });
  const append = (file: string, value: unknown) => appendFileSync(join(artifacts, file), JSON.stringify(value) + "\n");
  const offset = () => (performance.now() - started) / 1000;
  const progress = () => json(join(artifacts, "progress.json"), { task_id: task.task_id, turns, tool_calls: toolCalls,
    elapsed_seconds: offset(), usage, max_turns: config.max_turns,
    timed_out: timedOut, turn_limit_reached: existsSync(join(artifacts, "turn_limit.json")),
    submitted: existsSync(join(artifacts, "final_answer.json")) });
  const killGroup = (signal: NodeJS.Signals) => {
    if (!child.pid) return;
    try { process.kill(-child.pid, signal); } catch (error: any) { if (error.code !== "ESRCH") throw error; }
  };
  let killTimer: ReturnType<typeof setTimeout> | undefined;
  const terminate = () => { killGroup("SIGTERM"); killTimer ??= setTimeout(() => killGroup("SIGKILL"), 5000); };
  const timeout = config.timeout_seconds === null ? undefined : setTimeout(() => {
    timedOut = true; append("policy_events.jsonl", { kind: "hard_timeout", offset_seconds: offset() }); terminate();
  }, config.timeout_seconds * 1000);
  const onSignal = () => { interrupted = true; terminate(); };
  if (signal) {
    signal.addEventListener("abort", onSignal, { once: true });
    if (signal.aborted) onSignal();
  } else {
    process.once("SIGINT", onSignal);
    process.once("SIGTERM", onSignal);
  }
  const heartbeat = setInterval(progress, 1000);
  child.stderr.on("data", chunk => appendFileSync(join(artifacts, "stderr.log"), chunk));
  const lines = createInterface({ input: child.stdout });
  lines.on("line", line => {
    appendFileSync(join(artifacts, "raw-events.jsonl"), line + "\n");
    let event: any;
    try { event = JSON.parse(line); } catch { return; }
    if (event.type?.startsWith("auto_compaction") || event.type?.startsWith("auto_retry"))
      append("context_events.jsonl", { offset_seconds: offset(), ...event });
    if (event.type === "turn_start") lastModelStart = offset();
    if (event.type === "tool_execution_end") toolCalls++;
    if (event.type === "message_end") {
      append("conversation.jsonl", event.message);
      if (event.message.role === "assistant") {
        turns++;
        const u = event.message.usage;
        if (u) {
          observedUsage = true;
          usage.input_uncached += u.input ?? 0;
          usage.input_cached += u.cacheRead ?? 0;
          usage.cache_write += u.cacheWrite ?? 0;
          usage.logical_input += (u.input ?? 0) + (u.cacheRead ?? 0) + (u.cacheWrite ?? 0);
          usage.output += u.output ?? 0;
          usage.reported_cost_usd += u.cost?.total ?? 0;
        }
        append("model_calls.jsonl", { turn: turns, offset_seconds: lastModelStart,
          duration_seconds: lastModelStart === undefined ? null : offset() - lastModelStart,
          usage: u ?? null, stop_reason: event.message.stopReason, error: event.message.errorMessage ?? null });
      }
    }
    progress();
  });
  let code: number | null;
  try {
    code = await new Promise<number | null>((resolveDone, reject) => {
      child.once("error", reject);
      child.once("close", (exitCode, signal) => { exitSignal = signal; resolveDone(exitCode); });
    });
  } finally {
    clearTimeout(timeout); clearInterval(heartbeat);
    if (killTimer) clearTimeout(killTimer);
    // The process group was created by this task; clean up any remaining bridge descendant.
    killGroup("SIGKILL");
    if (signal) signal.removeEventListener("abort", onSignal);
    else { process.removeListener("SIGINT", onSignal); process.removeListener("SIGTERM", onSignal); }
    lines.close();
    rmSync(agentDir, { recursive: true }); rmSync(isolatedCwd, { recursive: true });
  }
  const finalPath = join(artifacts, "final_answer.json");
  const submitted = existsSync(finalPath);
  const turnLimit = existsSync(join(artifacts, "turn_limit.json"));
  const limitReason = turnLimit ? JSON.parse(readFileSync(join(artifacts, "turn_limit.json"), "utf8")).reason ?? "max_turns" : null;
  const result = { task_id: task.task_id, status: timedOut ? "timeout" : interrupted ? "interrupted" : submitted ? "submitted_answer" : turnLimit ? limitReason : "no_submission",
    exit_code: code!, exit_signal: exitSignal, elapsed_seconds: offset(), turns, tool_calls: toolCalls,
    max_turns: config.max_turns,
    usage: observedUsage ? usage : null, final_answer: submitted ? JSON.parse(readFileSync(finalPath, "utf8")) : null };
  json(join(artifacts, "result.json"), result);
  progress();
  return result;
}

export async function runTasks(config: Config, tasks: Task[], output: string, concurrency = 1, executeTask = runTask) {
  if (!Number.isSafeInteger(concurrency) || concurrency <= 0)
    throw new Error("concurrency must be a positive integer");
  const results: Awaited<ReturnType<typeof runTask>>[] = [];
  const active = new Set<AbortController>();
  let next = 0;
  let stopped = false;
  const stop = () => { stopped = true; for (const controller of active) controller.abort(); };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
  const worker = async () => {
    while (!stopped && next < tasks.length) {
      const index = next++;
      const task = tasks[index];
      const dir = join(output, "tasks", `${String(index).padStart(6, "0")}-${hash(task.task_id).slice(0, 12)}`);
      const controller = new AbortController();
      active.add(controller);
      try {
        console.log(`Starting ${task.task_id}: ${dir}`);
        const result = await executeTask(config, task, dir, undefined, controller.signal);
        results[index] = result;
        // Only completed tasks, in input order; never write null slots for pending tasks.
        json(join(output, "summary.json"), results.filter(result => result !== undefined));
        console.log(`${task.task_id}: ${result.status}, ${result.turns} turns, ${result.elapsed_seconds.toFixed(1)}s`);
        if (result.status === "interrupted") stop();
      } catch (error) {
        stop();
        throw error;
      } finally { active.delete(controller); }
    }
  };
  try {
    const workers = await Promise.allSettled(Array.from({ length: Math.min(concurrency, tasks.length) }, worker));
    const failed = workers.find(result => result.status === "rejected");
    if (failed?.status === "rejected") throw failed.reason;
    return results.filter(result => result !== undefined);
  } finally {
    process.removeListener("SIGINT", stop); process.removeListener("SIGTERM", stop);
  }
}

async function main() {
  const options: Record<string, string> = {};
  for (let i = 2; i < process.argv.length; i += 2) {
    const key = process.argv[i]; const value = process.argv[i + 1];
    if (!["--config", "--question", "--task-id", "--query-file", "--ids", "--output", "--model", "--thinking", "--concurrency"].includes(key) || !value) throw new Error(`Unknown/missing argument ${key}`);
    options[key] = value;
  }
  if (!options["--config"] || (!!options["--question"] === !!options["--query-file"]))
    throw new Error("Usage: bash scripts/run.sh --config configs/default.json (--question TEXT | --query-file FILE) [--ids ID,ID] [--output DIR] [--concurrency N]");
  const concurrency = Number(options["--concurrency"] ?? 1);
  if (!Number.isSafeInteger(concurrency) || concurrency <= 0)
    throw new Error("concurrency must be a positive integer");
  const config = loadConfig(resolve(options["--config"]));
  if (options["--model"]) config.model = options["--model"];
  if (options["--thinking"]) config.thinking = options["--thinking"];
  let tasks = options["--question"] ? [{ task_id: options["--task-id"] ?? "single", question: options["--question"] }] : loadTasks(resolve(options["--query-file"]));
  if (options["--ids"]) {
    const ids = new Set(options["--ids"].split(",")); tasks = tasks.filter(t => ids.has(t.task_id));
    if (tasks.length !== ids.size) throw new Error("Some requested IDs were not found (use the file's exact IDs)");
  }
  if (!tasks.length) throw new Error("No tasks selected");
  const output = resolve(options["--output"] ?? join(ROOT, "runs", new Date().toISOString().replace(/[:.]/g, "-")));
  if (existsSync(output)) throw new Error(`Output already exists; use a new directory: ${output}`);
  mkdirSync(output, { recursive: true }); mkdirSync(join(output, "tasks"));
  json(join(output, "run_setup.json"), {
    schema: "indexact-pi-run-v1",
    pi_version: config.pi_runtime?.version, runtime: config.pi_runtime, node_version: process.version, config, task_ids: tasks.map(x => x.task_id),
    package_lock_sha256: hash(readFileSync(join(ROOT, "agent/package-lock.json"))),
    models_file_sha256: config.models_file ? hash(readFileSync(config.models_file)) : null,
    profile: "indexact",
    indexact_prompt_sha256: config.interface.prompt_sha256,
    implementation_sha256: hash(["indexact/interface/bridge.py", "agent/src/bridge.ts", "agent/src/extension.ts", "agent/src/run.ts"].map(f => readFileSync(join(ROOT, f), "utf8")).join("\n")),
    turn_policy: { max_turns: config.max_turns },
    concurrency,
    settings: "IndexAct tools; full history, automatic compaction disabled",
  });
  const results = await runTasks(config, tasks, output, concurrency);
  if (results.some(x => x.status !== "submitted_answer")) process.exitCode = 1;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url))
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
