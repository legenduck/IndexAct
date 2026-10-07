"""Prepare fixed multi-hop question/answer files once, before running experiments."""

import argparse
import json
from pathlib import Path
import random

from _common import hub_file, identity, metadata, new_directory, read_jsonl, text, write_row


REPOSITORY = "RUC-NLPIR/FlashRAG_datasets"
REVISION = "bcafb8dd07d453be3cbeeeb3f78be1841bddf92c"
SPLITS = {"hotpotqa": "dev", "2wikimultihopqa": "dev", "musique": "dev", "bamboogle": "test"}
COUNTS = {"hotpotqa": 500, "2wikimultihopqa": 500, "musique": 500, "bamboogle": None}


def reference(answers):
    """Preserve every acceptable reference, including alternative surface forms."""
    if not isinstance(answers, list) or not answers:
        raise ValueError("golden_answers must be a nonempty list")
    for answer in answers:
        text(answer, "reference answer")
    if len(answers) == 1:
        return answers[0]
    return "Accepted equivalent answers (any one is sufficient): " + json.dumps(answers, ensure_ascii=False)


def flashrag_rows(path):
    for row in read_jsonl(path):
        answers = row["golden_answers"]
        yield {"task_id": identity(row["id"], "id"), "question": text(row["question"], "question"),
               "gold_answer": reference(answers), "gold_answers": answers}


def select(rows, *, count, seed):
    by_id = {}
    for source in rows:
        row = dict(source)
        qid = identity(row["task_id"], "task_id")
        if qid in by_id:
            raise ValueError(f"Duplicate task_id: {qid}")
        row["task_id"] = qid
        text(row["question"], "question")
        text(row["gold_answer"], "gold_answer")
        by_id[qid] = row
    ids = sorted(by_id)
    if not ids:
        raise ValueError("Question source is empty")
    if count is not None and (count < 1 or count > len(ids)):
        raise ValueError(f"Need {count} questions, but the source contains {len(ids)}; "
                         "no repetition or automatic reduction is allowed.")
    selected = ids if count is None else sorted(random.Random(seed).sample(ids, count))
    return [by_id[qid] for qid in selected], len(ids)


def prepare(rows, output, *, count, seed, source_info):
    selected, total = select(rows, count=count, seed=seed)
    with new_directory(output) as stage:
        with (stage / "questions.jsonl").open("w", encoding="utf-8") as stream:
            for row in selected:
                write_row(stream, row)
        report = {"source": source_info, "source_questions": total, "questions": len(selected),
                  "seed": seed if count is not None else None,
                  "selection": "all" if count is None else "random.Random(seed).sample over lexically sorted IDs",
                  "output_order": "lexically sorted task_id", "selected_ids": [r["task_id"] for r in selected],
                  "labels": "selected together with questions; no text deduplication or answer-based filtering"}
        metadata(stage, report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark", choices=SPLITS, required=True,
                        help="500 questions each; Bamboogle uses its full 125-question split")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=Path("data/_downloads"))
    parser.add_argument("--source", type=Path, help="Use local original FlashRAG JSONL")
    args = parser.parse_args()
    if args.output.exists() or args.output.is_symlink():
        parser.error(f"Output already exists: {args.output}")
    filename = f"{args.benchmark}/{SPLITS[args.benchmark]}.jsonl"
    path = args.source or hub_file(REPOSITORY, REVISION, filename, args.cache)
    rows = flashrag_rows(path)
    info = {"benchmark": args.benchmark, "split": SPLITS[args.benchmark],
            **({"local": str(args.source)} if args.source else
               {"repository": REPOSITORY, "revision": REVISION, "file": filename})}
    report = prepare(rows, args.output, count=COUNTS[args.benchmark],
                     seed=args.seed, source_info=info)
    print(f"Prepared {report['questions']} of {report['source_questions']} questions in {args.output}")


if __name__ == "__main__":
    main()
