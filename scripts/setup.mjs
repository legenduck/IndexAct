#!/usr/bin/env node
import {execFileSync} from 'node:child_process';
import {existsSync, mkdirSync, readFileSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const lock = JSON.parse(readFileSync(join(root, 'agent/package.json'), 'utf8')).config.piRuntime;
const pi = resolve(root, lock.checkout_directory);
const run = (exe, args, cwd = root) => execFileSync(exe, args, {cwd, stdio: 'inherit'});
const python = join(root, '.venv/bin/python');
run(existsSync(python) ? python : 'python3', ['-c',
  'import sys; sys.exit("IndexAct requires Python 3.11+." if sys.version_info < (3, 11) else 0)']);
if (!existsSync(pi)) {
  mkdirSync(dirname(pi), {recursive: true});
  run('git', ['clone', '--filter=blob:none', '--no-checkout', lock.repository, pi]);
  run('git', ['checkout', '--detach', lock.revision], pi);
}
const commit = execFileSync('git', ['rev-parse', 'HEAD'], {cwd: pi, encoding: 'utf8'}).trim();
if (commit !== lock.revision) throw new Error('Existing Pi checkout differs from the lock; it was not overwritten.');
run('npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund'], pi);
// Compile the pinned source and checked-in model catalogue; do not regenerate
// models from mutable network data or patch upstream source.
for (const pkg of ['tui', 'ai', 'agent', 'coding-agent']) {
  run(join(pi, 'node_modules/.bin/tsgo'), ['-p', 'tsconfig.build.json'], join(pi, 'packages', pkg));
}
run('npm', ['run', 'copy-assets'], join(pi, 'packages/coding-agent'));
run('npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund'], join(root, 'agent'));
if (!existsSync(python)) run('python3', ['-m', 'venv', '.venv']);
run(python, ['-m', 'pip', 'install', '-e', '.']);
console.log('IndexAct + official Pi installed. See README.md for index building and execution.');
