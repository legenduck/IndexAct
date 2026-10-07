# IndexAct

IndexAct is an interface for Index-Native Corpus Interaction that separates
candidate-set refinement from text inspection. Agents construct, refine, and
combine persistent candidate sets over an inverted index, receiving reusable
state references and statistics such as candidate counts rather than automatically
returned passages. This feedback helps agents assess search conditions, revisit
earlier states, and explore alternative directions. Passages are requested
separately for new clues or supporting evidence, and information learned from
reading can guide subsequent operations on retained sets. Reading and refinement
can therefore alternate throughout exploration, rather than follow a fixed
pipeline. Experiments on five benchmarks spanning agentic search and multi-hop
question answering show that IndexAct outperforms the evaluated baselines. On
BrowseComp-Plus, it also achieves higher evidence coverage with a smaller average
live context than terminal-based corpus interfaces.

## Installation

Use Python 3.11+ and Node.js 22+ (with npm) on Linux x86-64. Git, curl, and
unzip must also be available. From the repository root, create a Python
environment and install the project:

```bash
python3 -m venv .venv
source .venv/bin/activate
node scripts/setup.mjs
python -m pip install -e '.[data,evaluation]'
```

The setup script installs the Pi runtime. The index scripts download JDK 21 and
Maven when needed. Commands below assume the repository root and an active
`.venv`; in a new terminal, run `source .venv/bin/activate` again.

### API key

Create your local environment file:

```bash
cp .env.template .env
```

Set `OPENAI_API_KEY` in `.env`, then load it in the shell used for experiments
and answer judging:

```bash
set -a
source .env
set +a
```

`.env` is ignored by Git and is not loaded automatically. Repeat the load command
in each new shell. Both experiments and answer judging make paid API calls.

## Prepare data

### BC+

Download the 100,195-document corpus and all 830 questions, including reference
answers and evidence annotations:

```bash
python datasets/bcplus.py --output data/bcplus
```

This creates `corpus.jsonl`, `questions.jsonl`, and `qrel_evidence.txt` under
`data/bcplus/`.

### Multi-hop QA

Download the shared Wikipedia-18 corpus and prepare fixed question sets:

```bash
python datasets/wikipedia.py --output data/wiki
for benchmark in hotpotqa 2wikimultihopqa musique bamboogle; do
  python datasets/multihop.py \
    --benchmark "$benchmark" --seed 42 \
    --output "data/questions/$benchmark"
done
```

The corpus is saved as `data/wiki/corpus.jsonl`. Each benchmark directory contains
a `questions.jsonl` with reference answers: 500 sampled questions each for
HotpotQA, 2WikiMultiHopQA, and MuSiQue, and all 125 for Bamboogle.
Prepare these files once and reuse them across runs; preparation requires a new
output directory.

## Build and serve the index

Build the BC+ index:

```bash
bash scripts/build_index.sh --input data/bcplus/corpus.jsonl --store snapshots
```

For multi-hop QA, use `--input data/wiki/corpus.jsonl` instead. Building an index
is a one-time step; reuse it across experiment runs. Set `INDEXACT_JAVA_OPTS` to
fit the corpus and available memory before building or serving; the default heap
limit is `-Xmx8g`.

Copy the `snapshot_id` printed by the builder into the command below. Start the
search service in a separate terminal and leave it running:

```bash
bash scripts/serve.sh --store snapshots --snapshot-id snap-REPLACE_WITH_BUILD_ID \
  --dataset bcplus --host 127.0.0.1 --port 8765 --path /
```

For the Wikipedia-18 index used by multi-hop QA, use `--dataset wikipedia-18`.

The service stays running with closed standard input, including under `nohup`.
Stop it with Ctrl+C in the foreground or send SIGTERM to its process.

Set `service.snapshot_id` and `service.endpoint` in
[`configs/interface.yaml`](configs/interface.yaml) to match this service.

## Run experiments

[`configs/default.json`](configs/default.json) selects the agent and judge models:
`gpt-5.4-nano / high` for the agent and `gpt-5.4-nano / low` for judging.
Service settings and experiment budgets are in
[`configs/interface.yaml`](configs/interface.yaml).

With the BC+ service running and the API key loaded:

```bash
bash scripts/run.sh --config configs/default.json \
  --query-file data/bcplus/questions.jsonl --output runs/bcplus
```

For multi-hop QA, connect the configuration to the Wikipedia index and select the
corresponding question file. For example:

```bash
bash scripts/run.sh --config configs/default.json \
  --query-file data/questions/musique/questions.jsonl --output runs/musique
```

Use a new output directory for each run. Run settings are saved in
`run_setup.json`, and per-question logs are under `tasks/` within that directory.
Add `--concurrency 4` to run up to four questions at once (default: `1`).
Questions share the search service but keep separate sessions and logs;
tool execution within each question remains sequential.

## Evaluation

Evaluate completed runs using the same question files. BC+ uses the official BC+
judge prompt; multi-hop QA uses the official BrowseComp judge prompt. Both use
the judge configured in `configs/default.json`.

### BC+

Judge the answers, then compute Accuracy, Evidence Coverage, and Average Live
Context:

```bash
python -m evaluation.judges.llm \
  --benchmark bcplus --config configs/default.json \
  --query-file data/bcplus/questions.jsonl \
  --run-dir runs/bcplus --output runs/bcplus/evaluation/judge

python -m evaluation.metrics \
  --run-dir runs/bcplus --query-file data/bcplus/questions.jsonl \
  --corpus data/bcplus/corpus.jsonl --benchmark bcplus \
  --qrels-evidence data/bcplus/qrel_evidence.txt \
  --judge-dir runs/bcplus/evaluation/judge
```

The combined results are saved in `runs/bcplus/evaluation/summary.json`.

### Multi-hop QA

Multi-hop QA reports Accuracy only. For the MuSiQue run above:

```bash
python -m evaluation.judges.llm \
  --benchmark multihop --config configs/default.json \
  --query-file data/questions/musique/questions.jsonl \
  --run-dir runs/musique --output runs/musique/evaluation/judge
```

Results are saved in `runs/musique/evaluation/judge/summary.json`.
Use the corresponding question file and run directory for the other benchmarks.
