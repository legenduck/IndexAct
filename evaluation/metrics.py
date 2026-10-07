"""BC+ evidence coverage and average live context, with existing answer judgments."""

import argparse
from collections import Counter
from importlib.metadata import version
import json
from pathlib import Path
import shutil
import sys
import tempfile

from .requests import AccountingError, TokenCounter, document, dynamic_pieces, rows
from .sources import Corpus, Deliveries, evidence_records, read_qrels, source_identity


OUTPUT_FILES = ("per_request.jsonl", "per_question.jsonl", "summary.json")


def write_row(stream, value):
    stream.write(json.dumps(value, ensure_ascii=False, allow_nan=False) + "\n")


def load_tasks(run_dir, query_file, benchmark, qrels_path):
    if benchmark != "bcplus":
        raise AccountingError("This command measures BC+ only; multi-hop Accuracy uses evaluation.judges.llm")
    setup = document(run_dir / "run_setup.json")
    ids = setup.get("task_ids")
    if not isinstance(ids, list) or not ids or any(not isinstance(k, str) or not k for k in ids) or len(set(ids)) != len(ids):
        raise AccountingError("run_setup.json requires unique, nonempty task_ids")
    labels = {}
    for row in rows(query_file):
        qid = next((row[k] for k in ("task_id", "query_id", "id") if row.get(k) is not None), None)
        if type(qid) not in (str, int) or not str(qid) or str(qid) in labels:
            raise AccountingError("Question file contains missing or duplicate task IDs")
        labels[str(qid)] = row
    qrels = read_qrels(qrels_path) if qrels_path else None
    if qrels is None:
        raise AccountingError("BC+ requires --qrels-evidence with the full official evidence qrels")
    folders = {}
    for path in (run_dir / "tasks").glob("*/task.json"):
        task = document(path)
        qid = task.get("task_id")
        if qid not in ids or qid in folders:
            raise AccountingError(f"Unexpected/duplicate task directory: {path}")
        label = labels.get(qid, {})
        question = label.get("question", label.get("query"))
        answer = label.get("gold_answer", label.get("answer"))
        if not isinstance(question, str) or not question or task.get("question") != question:
            raise AccountingError(f"Question text/ID mismatch: {qid}")
        if not isinstance(answer, str) or not answer.strip():
            raise AccountingError(f"Missing reference answer: {qid}")
        if "answer" in label and "gold_answer" in label and label["answer"] != label["gold_answer"]:
            raise AccountingError(f"Conflicting reference answers: {qid}")
        annotated = label.get("evidence_doc_keys")
        if annotated is not None:
            if (not isinstance(annotated, list) or any(not isinstance(k, str) or not k for k in annotated)
                    or len(set(annotated)) != len(annotated)):
                raise AccountingError(f"Invalid evidence_doc_keys: {qid}")
            annotated = set(annotated)
        if qrels is not None:
            official = qrels.get(qid)
            if not official or (annotated is not None and annotated != official):
                raise AccountingError(f"Full evidence qrel mismatch/missing annotations: {qid}")
            annotated = official
        result = document(path.parent / "result.json")
        if result.get("task_id") != qid:
            raise AccountingError(f"Result task ID mismatch: {qid}")
        for filename in ("provider_requests.jsonl", "tool_calls.jsonl"):
            if not (path.parent / filename).is_file():
                raise AccountingError(f"Missing required log: {path.parent / filename}")
        folders[qid] = {"task_id": qid, "question": question, "gold_answer": answer,
                        "evidence": annotated, "directory": path.parent, "result": result}
    if set(folders) != set(ids):
        raise AccountingError("Run is incomplete: not all planned questions have final task artifacts")
    return setup, [folders[qid] for qid in ids]


def judge_results(path, tasks, benchmark):
    if path is None:
        return {}
    setup = document(path / "judge_setup.json")
    if setup.get("benchmark") != benchmark:
        raise AccountingError("Judge benchmark does not match the evaluated benchmark")
    by_id = {task["task_id"]: task for task in tasks}
    judgments = {}
    for row in rows(path / "judgments.jsonl"):
        qid = row.get("task_id")
        if qid not in by_id or qid in judgments:
            raise AccountingError("Judge results contain unexpected/duplicate task IDs")
        task = by_id[qid]
        expected_answer = (task["result"].get("final_answer") or {}).get("answer", "")
        if (row.get("question") != task["question"] or row.get("gold_answer") != task["gold_answer"]
                or row.get("answer") != expected_answer):
            raise AccountingError(f"Judge/run/label mismatch: {qid}")
        status = row.get("status")
        if status == "scored" and type(row.get("correct")) is bool:
            judgments[qid] = int(row["correct"])
        elif status == "no_answer" and not expected_answer.strip() and row.get("correct") is False:
            judgments[qid] = 0
        elif status == "judge_error":
            judgments[qid] = None
        else:
            raise AccountingError(f"Invalid judge verdict: {qid}")
    if set(judgments) != set(by_id):
        raise AccountingError("Judge results are incomplete; no questions may be silently excluded")
    return judgments


def evaluate_question(task, corpus, tokenize, request_stream):
    folder, qid = task["directory"], task["task_id"]
    request_path = folder / "provider_requests.jsonl"
    with request_path.open("rb") as stream:
        request_count = sum(1 for _ in stream)
    issues = []
    prompts = []
    for name in ("system_prompt.txt", "user_prompt.txt"):
        path = folder / name
        if request_count and not path.is_file():
            raise AccountingError(f"Missing fixed-prompt record: {path}")
        prompts.append(path.read_bytes().decode("utf-8") if path.is_file() else "")
    deliveries = Deliveries(folder / "tool_calls.jsonl", corpus)
    context_tokens = []
    observed = set()
    context_complete = True
    for number, request in enumerate(rows(request_path), 1):
        payload = request.get("payload")
        if not isinstance(payload, dict):
            raise AccountingError(f"{qid}: request {number} has no payload")
        record = {"task_id": qid, "request_index": number, "model": payload.get("model"),
                  "offset_seconds": request.get("offset_seconds"), "dynamic_tokens": None,
                  "tokens_by_role": None, "readable_reasoning_tokens": None,
                  "observed_doc_keys": None, "newly_observed_doc_keys": None, "issues": []}
        try:
            pieces = dynamic_pieces(payload, *prompts, first=number == 1)
            by_role = Counter()
            reasoning = 0
            for piece in pieces:
                count = tokenize(piece.text)
                by_role[piece.role] += count
                if piece.kind == "reasoning":
                    reasoning += count
            current = deliveries.observed(pieces, corpus)
            record.update(dynamic_tokens=sum(by_role.values()), tokens_by_role=dict(by_role),
                          readable_reasoning_tokens=reasoning, observed_doc_keys=sorted(current),
                          newly_observed_doc_keys=sorted(current - observed))
            observed.update(current)
            context_tokens.append(record["dynamic_tokens"])
        except AccountingError as error:
            context_complete = False
            record["issues"].append(str(error))
            issues.append(f"Request {number}: {error}")
        write_row(request_stream, record)
    if not request_count:
        issues.append("AvgCtx undefined: this question has no recorded model request")
    ev = task["evidence"]
    evicov = len(observed & ev) / len(ev) if ev and context_complete else None
    return {"task_id": qid, "run_status": task["result"].get("status"),
            "request_count": request_count,
            "avg_context_tokens": sum(context_tokens) / request_count if request_count and context_complete else None,
            "evidence_coverage_percent": 100 * evicov if evicov is not None else None,
            "evidence_documents": len(ev) if ev is not None else None,
            "observed_doc_keys": sorted(observed),
            "observed_evidence_doc_keys": sorted(observed & ev) if ev is not None else None,
            "issues": issues}


def summarize(results, setup, paths, benchmark, judged):
    def complete_mean(field):
        values = [row[field] for row in results]
        return sum(values) / len(values) if values and all(v is not None for v in values) else None

    required = ["avg_context_tokens", "evidence_coverage_percent"]
    return {"questions": len(results), "benchmark": benchmark,
            "complete": all(all(row[k] is not None for k in required) and not row["issues"] for row in results),
            "accuracy_percent": 100 * complete_mean("answer_correct") if judged and complete_mean("answer_correct") is not None else None,
            "accuracy_status": "not_provided" if not judged else "complete" if complete_mean("answer_correct") is not None else "judge_error",
            "avg_context_tokens": complete_mean("avg_context_tokens"),
            "evidence_coverage_percent": complete_mean("evidence_coverage_percent"),
            "available_questions": {k: sum(r[k] is not None for r in results) for k in required},
            "questions_with_issues": [r["task_id"] for r in results if r["issues"]],
            "inputs": paths,
            "snapshot_id": setup.get("config", {}).get("service", {}).get("snapshot_id"),
            "accounting": {
                "tokenizer": "o200k_base", "tiktoken_version": version("tiktoken"),
                "context": "Sum tokens of readable content fields, function names and argument strings; no synthetic role separators, envelope IDs or provider framing. Exclude the saved fixed system/user prefixes once per request; retain dynamic suffixes, summaries and readable reasoning. Exclude opaque reasoning.",
                "context_denominator": "Recorded decision-making requests, including logged retries/failures; request_count is retained only to audit each question's context mean.",
                "evidence": "Full evidence annotations; exact canonical codepoint-span verification; only source text in actual request tool outputs counts. Returned-but-never-input text does not count.",
                "averaging": "Question-level means, then equal-weight macro means; failed/budget-exhausted questions are retained. Missing measurements make that macro metric null, not a reduced denominator.",
            }}


def evaluate(run_dir, query_file, corpus_path, output, *, benchmark, qrels_path=None, judge_dir=None):
    run_dir, output = run_dir.resolve(), output.resolve()
    if output == run_dir or output.is_relative_to(run_dir / "tasks"):
        raise AccountingError("Evaluation output must not be the run root or an original task directory")
    for name in OUTPUT_FILES:
        if (output / name).exists() or (output / name).is_symlink():
            raise AccountingError(f"Output already exists: {output / name}; select a new output directory")
    setup, tasks = load_tasks(run_dir, query_file, benchmark, qrels_path)
    if judge_dir is None and (output / "judge").is_dir():
        judge_dir = output / "judge"
    judgments = judge_results(judge_dir, tasks, benchmark)
    wanted = set()
    for task in tasks:
        for call in rows(task["directory"] / "tool_calls.jsonl"):
            if "observation" not in call:
                raise AccountingError("Tool logs must contain model-visible observations")
            wanted.update(source_identity(r)[0] for r in evidence_records(call["observation"]))
    tokenize = TokenCounter()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".indexact-evaluation-", dir=output.parent) as temporary:
        stage = Path(temporary)
        print(f"Verifying canonical bodies for {len(wanted)} delivered documents", file=sys.stderr, flush=True)
        corpus = Corpus(corpus_path, wanted, stage / "corpus.sqlite")
        results = []
        try:
            with (stage / "per_request.jsonl").open("w", encoding="utf-8") as requests, (stage / "per_question.jsonl").open("w", encoding="utf-8") as questions:
                for task in tasks:
                    row = evaluate_question(task, corpus, tokenize, requests)
                    row["answer_correct"] = judgments.get(task["task_id"])
                    write_row(questions, row)
                    results.append(row)
                    print(f"{row['task_id']}: {row['request_count']} requests, {len(row['issues'])} accounting issues", file=sys.stderr, flush=True)
        finally:
            corpus.close()
        paths = {"run_dir": str(run_dir), "query_file": str(query_file.resolve()), "corpus": str(corpus_path.resolve()),
                 "qrels_evidence": str(qrels_path.resolve()) if qrels_path else None,
                 "judge_dir": str(judge_dir.resolve()) if judge_dir else None}
        summary = summarize(results, setup, paths, benchmark, bool(judgments))
        (stage / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        # Existing judge/ output is retained. Never overwrite prior metric artifacts.
        output.mkdir(parents=True, exist_ok=True)
        for name in OUTPUT_FILES:
            with (output / name).open("xb") as dest, (stage / name).open("rb") as source:
                shutil.copyfileobj(source, dest)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--query-file", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True, help="Canonical doc_key/raw_text JSONL used to build this run's index")
    parser.add_argument("--benchmark", choices=("bcplus",), default="bcplus")
    parser.add_argument("--qrels-evidence", type=Path, required=True, help="Full official BC+ evidence qrel file")
    parser.add_argument("--judge-dir", type=Path, help="Existing judge output; default: OUTPUT/judge if present")
    parser.add_argument("--output", type=Path, help="Default: RUN_DIR/evaluation; must not contain prior metric files")
    args = parser.parse_args()
    try:
        summary = evaluate(args.run_dir, args.query_file, args.corpus, args.output or args.run_dir / "evaluation",
                           benchmark=args.benchmark, qrels_path=args.qrels_evidence, judge_dir=args.judge_dir)
    except (AccountingError, OSError, UnicodeError, ImportError) as error:
        parser.exit(2, f"Evaluation failed: {error}\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["complete"] and summary["accuracy_status"] != "judge_error" else 1


if __name__ == "__main__":
    raise SystemExit(main())
