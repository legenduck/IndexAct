import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";
import { appendFileSync } from "node:fs";
import { join } from "node:path";

export type ToolSpec = { name: string; description: string; parameters: Record<string, unknown> };
export type BridgeResult = { observation: { status: string; value?: any; error?: any }; submitted: boolean; is_error: boolean };
export class IndexActBridge {
  private child: ChildProcessWithoutNullStreams;
  private counter = 0;
  private pending = new Map<number, { resolve: (value: BridgeResult) => void; reject: (error: Error) => void }>();
  private fatal?: Error;
  readonly ready: Promise<{ tools: ToolSpec[]; schema_sha256: string; snapshot_id: string }>;
  constructor(python: string, script: string, config: string, artifacts: string) {
    this.child = spawn(python, ["-u", script, "--config", config, "--artifacts", artifacts], { stdio: "pipe" });
    let resolveReady: (value: any) => void;
    let rejectReady: (error: Error) => void;
    this.ready = new Promise((resolve, reject) => { resolveReady = resolve; rejectReady = reject; });
    const fail = (error: Error) => {
      this.fatal = error;
      rejectReady(error);
      for (const item of this.pending.values()) item.reject(error);
      this.pending.clear();
    };
    this.child.stderr.on("data", chunk => appendFileSync(join(artifacts, "bridge.stderr.log"), chunk));
    createInterface({ input: this.child.stdout }).on("line", line => {
      try {
        const value = JSON.parse(line);
        if (value.type === "ready") { resolveReady(value); return; }
        const item = this.pending.get(value.id);
        if (!item) return;
        this.pending.delete(value.id);
        if (value.error) item.reject(new Error(value.error)); else item.resolve(value.result);
      } catch (error) { fail(new Error(`Invalid IndexAct bridge response: ${String(error)}`)); }
    });
    this.child.on("error", fail);
    this.child.on("exit", (code, signal) => fail(new Error(`IndexAct bridge closed (${code ?? signal}); outcome may be UNKNOWN; not retried`)));
    this.child.stdin.on("error", fail);
  }
  async execute(name: string, args: unknown, callId: string): Promise<BridgeResult> {
    await this.ready;
    if (this.fatal) throw this.fatal;
    const id = ++this.counter;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.child.stdin.write(JSON.stringify({ id, op: "execute", name, arguments: args, call_id: callId }) + "\n");
    });
  }
  async close(): Promise<void> {
    if (this.child.exitCode !== null || this.child.signalCode !== null) return;
    await new Promise<void>(resolve => {
      const terminate = setTimeout(() => this.child.kill("SIGTERM"), 1500);
      const kill = setTimeout(() => this.child.kill("SIGKILL"), 3000);
      this.child.once("exit", () => { clearTimeout(terminate); clearTimeout(kill); resolve(); });
      this.child.stdin.end();
    });
  }
}
