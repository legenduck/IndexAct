import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const lock = JSON.parse(readFileSync(join(root, "agent/package.json"), "utf8")).config.piRuntime;
export const PI_RUNTIME = resolve(root, lock.checkout_directory);

// Dependency pin only. IndexAct source/prompt edits never require a new draft.
export function runtimeIdentity() {
  const commit = execFileSync("git", ["-C", PI_RUNTIME, "rev-parse", "HEAD"], {encoding: "utf8"}).trim();
  if (commit !== lock.revision) throw new Error("Official Pi revision differs from agent/package.json; run setup.");
  const files: string[] = [];
  function walk(dir: string) {
    for (const entry of readdirSync(dir, {withFileTypes: true})) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (entry.isFile() && entry.name.endsWith(".js")) files.push(path);
    }
  }
  for (const pkg of ["coding-agent", "agent", "ai"]) walk(join(PI_RUNTIME, "packages", pkg, "dist"));
  const digest = createHash("sha256");
  for (const file of files.sort()) digest.update(relative(PI_RUNTIME, file) + "\0").update(readFileSync(file));
  return {kind: "official-pi", repository: lock.repository, root: PI_RUNTIME,
    version: lock.package_version, commit, compiled_sha256: digest.digest("hex")};
}
