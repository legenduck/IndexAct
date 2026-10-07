"""Offline LLM-only answer judging for Pi outputs.

BC+ uses its official grader prompt; multi-hop QA uses the official BrowseComp
grader prompt. No answer normalization, EM, or F1 fallback is performed.
Judging never runs inside the agent loop.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import json
import math
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

from evaluation.requests import rows
from . import bcplus, browsecomp
from .bcplus import parse_judge_response


BENCHMARK_PROMPTS = {"bcplus": bcplus, "multihop": browsecomp}


def select_prompt(benchmark: str):
    """Require an explicit benchmark; never guess from paths or question IDs."""
    if benchmark not in BENCHMARK_PROMPTS:
        raise ValueError("benchmark must be bcplus or multihop")
    return BENCHMARK_PROMPTS[benchmark]


@dataclass(frozen=True)
class JudgeConfig:
    model: str
    reasoning_effort: str
    max_completion_tokens: int
    request_timeout_seconds: float

    def __post_init__(self):
        if not isinstance(self.model, str) or not self.model.strip() or "/" in self.model:
            raise ValueError("judge.model must be an OpenAI model ID, without a provider/ prefix")
        if self.reasoning_effort not in {"none", "minimal", "low", "medium", "high", "xhigh", "max"}:
            raise ValueError("Invalid judge.reasoning_effort")
        if type(self.max_completion_tokens) is not int or self.max_completion_tokens < 1:
            raise ValueError("judge.max_completion_tokens must be a positive integer")
        timeout = self.request_timeout_seconds
        if type(timeout) not in (int, float) or not math.isfinite(timeout) or timeout <= 0:
            raise ValueError("judge.request_timeout_seconds must be finite and positive")


def load_judge_config(path: Path) -> JudgeConfig:
    raw = json.loads(path.read_text(encoding="utf-8"))
    judge = raw.get("judge")
    if not isinstance(judge, dict) or set(judge) != set(JudgeConfig.__dataclass_fields__):
        raise ValueError("judge requires model, reasoning_effort, max_completion_tokens, request_timeout_seconds")
    return JudgeConfig(**judge)


def judge_answer(
    question: str, response: str, correct_answer: str, config: JudgeConfig, *, benchmark: str
) -> dict:
    """One judge request. HTTP/parser/truncation failures remain unscored errors."""
    prompt = select_prompt(benchmark)
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise ValueError("Set OPENAI_API_KEY to run the judge")
    base_url = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
    url = urlsplit(base_url)
    if (url.scheme not in {"http", "https"} or not url.netloc
            or url.username is not None or url.password is not None or url.query or url.fragment):
        raise ValueError("OPENAI_BASE_URL must be an HTTP(S) API base URL without credentials/query")
    payload = {
        "model": config.model,
        "reasoning_effort": config.reasoning_effort,
        "max_completion_tokens": config.max_completion_tokens,
        "messages": [{
            "role": "user", "content": prompt.create_judge_prompt(question, response, correct_answer),
        }],
    }
    record = {
        "status": "judge_error", "correct": None, "score": None,
        "benchmark": benchmark, "prompt_source": prompt.SOURCE_URL,
        "request": payload, "endpoint": base_url + "/chat/completions",
    }
    request = urllib.request.Request(
        record["endpoint"], data=json.dumps(payload, ensure_ascii=False).encode(),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=config.request_timeout_seconds) as result:
            body = result.read().decode("utf-8")
        record["raw_response_text"] = body
        result = json.loads(body)
        record["response"] = result
        record["usage"] = result.get("usage")
        record["response_model"] = result.get("model")
        choice = result["choices"][0]
        record["finish_reason"] = choice.get("finish_reason")
        if choice.get("finish_reason") != "stop":
            raise ValueError(f"Judge did not complete normally: {choice.get('finish_reason')}")
        text = choice["message"].get("content")
        if not isinstance(text, str):
            raise ValueError("Judge returned no text content")
        parsed = parse_judge_response(text)
        record["parsed"] = parsed
        if parsed["parse_error"]:
            raise ValueError("Judge response has no parseable verdict")
        record.update(status="scored", correct=parsed["correct"], score=int(parsed["correct"]))
    except urllib.error.HTTPError as error:
        record["error"] = f"HTTP {error.code}: {error.read().decode('utf-8', errors='replace')}".replace(api_key, "[REDACTED]")
    except (OSError, ValueError, KeyError, IndexError, TypeError, AttributeError) as error:
        record["error"] = f"{type(error).__name__}: {error}".replace(api_key, "[REDACTED]")
    record["latency_seconds"] = time.monotonic() - started
    return record


def load_inputs(query_file: Path, run_dir: Path) -> list[dict]:
    """Validate the entire completed run and label alignment before any API call."""
    labels = {}
    for row in rows(query_file):
        key = next((row[k] for k in ("task_id", "query_id", "id") if row.get(k) is not None), None)
        if type(key) not in (str, int) or not str(key):
            raise ValueError("Judge questions need an explicit task_id/query_id/id")
        key = str(key)
        question = row.get("question", row.get("query"))
        answer = row.get("gold_answer", row.get("answer"))
        if not isinstance(question, str) or not question.strip():
            raise ValueError(f"Missing question: {key}")
        if not isinstance(answer, str) or not answer.strip():
            raise ValueError(f"Missing string gold_answer/answer: {key}")
        if "gold_answer" in row and "answer" in row and row["gold_answer"] != row["answer"]:
            raise ValueError(f"Conflicting answer labels: {key}")
        if key in labels:
            raise ValueError(f"Duplicate question ID: {key}")
        labels[key] = (question, answer)
    setup = json.loads((run_dir / "run_setup.json").read_text(encoding="utf-8"))
    ids = setup["task_ids"]
    if not isinstance(ids, list) or not ids or any(not isinstance(k, str) for k in ids):
        raise ValueError("run_setup.json must contain nonempty task_ids")
    if len(set(ids)) != len(ids):
        raise ValueError("Duplicate task IDs in run_setup.json")
    tasks = {}
    for path in sorted((run_dir / "tasks").glob("*/task.json")):
        task = json.loads(path.read_text(encoding="utf-8"))
        key = task["task_id"]
        if key in tasks or key not in ids:
            raise ValueError(f"Duplicate/unexpected run task: {key}")
        if key not in labels or task["question"] != labels[key][0]:
            raise ValueError(f"Run question does not match label file: {key}")
        result_path = path.parent / "result.json"
        if not result_path.is_file():
            raise ValueError(f"Task has no final result.json; finish the run before judging: {key}")
        result = json.loads(result_path.read_text(encoding="utf-8"))
        if result["task_id"] != key:
            raise ValueError(f"result.json task ID mismatch: {key}")
        final = result.get("final_answer")
        candidate = "" if final is None else final["answer"]
        if not isinstance(candidate, str):
            raise ValueError(f"Final answer must be text: {key}")
        tasks[key] = {
            "task_id": key, "question": task["question"], "gold_answer": labels[key][1],
            "answer": candidate, "run_status": result["status"],
        }
    if set(tasks) != set(ids):
        raise ValueError("Run is incomplete: expected task artifacts are missing")
    return [tasks[key] for key in ids]


def summarize(results: list[dict]) -> dict:
    correct = sum(row["correct"] is True for row in results)
    errors = sum(row["status"] == "judge_error" for row in results)
    return {
        "questions": len(results), "correct": correct, "judge_errors": errors,
        "no_answer": sum(row["status"] == "no_answer" for row in results),
        "accuracy_percent": 100 * correct / len(results) if results and not errors else None,
        "complete": bool(results) and not errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--benchmark", choices=tuple(BENCHMARK_PROMPTS), required=True,
        help="bcplus: official BC+ prompt; multihop: official BrowseComp prompt",
    )
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--query-file", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New judge-only output directory")
    args = parser.parse_args()
    prompt = select_prompt(args.benchmark)
    config = load_judge_config(args.config)
    tasks = load_inputs(args.query_file, args.run_dir)
    if any(task["answer"].strip() for task in tasks) and not os.environ.get("OPENAI_API_KEY"):
        parser.error("Set OPENAI_API_KEY; judging makes paid API requests")
    args.output.mkdir(parents=True, exist_ok=False)
    metadata = {
        "judge": asdict(config), "benchmark": args.benchmark,
        "prompt_source": prompt.SOURCE_URL,
        "run_dir": str(args.run_dir.resolve()),
    }
    (args.output / "judge_setup.json").write_text(json.dumps(metadata, indent=2) + "\n")
    results = []
    with (args.output / "judgments.jsonl").open("x", encoding="utf-8") as stream:
        for task in tasks:
            if task["answer"].strip():
                verdict = judge_answer(
                    task["question"], task["answer"], task["gold_answer"], config,
                    benchmark=args.benchmark,
                )
            else:
                # No submitted answer earns zero; do not pay a judge to score empty output.
                verdict = {"status": "no_answer", "correct": False, "score": 0}
            row = {**task, **verdict}
            stream.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")
            stream.flush()
            results.append(row)
            print(f"{task['task_id']}: {row['status']}, correct={row['correct']}", flush=True)
    summary = summarize(results)
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary))
    return 1 if summary["judge_errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
