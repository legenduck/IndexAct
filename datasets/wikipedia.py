"""Download the experiment's Wikipedia-18 JSONL and preserve its IDs and text."""

import argparse
from pathlib import Path

from _common import hub_file, metadata, new_directory, read_jsonl, text, write_row


# This is the same Wikipedia-18 mirror/file used by the experiments, not a new dump.
REPOSITORY = "DCI-Agent/corpus"
REVISION = "0a83b2666b760181f3e74419df1e9f1e09ba1f69"
FILENAME = "wiki/wiki_dump.jsonl"


def prepare(source, output, source_info, *, expected=None):
    count = 0
    with new_directory(output) as stage:
        with (stage / "corpus.jsonl").open("w", encoding="utf-8") as stream:
            for row in read_jsonl(source):
                write_row(stream, {"doc_key": text(row["id"], "id"),
                                   "raw_text": text(row["contents"], "contents", empty=True)})
                count += 1
        if not count or (expected is not None and count != expected):
            raise ValueError(f"Unexpected Wikipedia document count: {count}; expected {expected}")
        report = {"source": source_info, "documents": count,
                  "mapping": "id -> doc_key; contents -> raw_text, unchanged",
                  "duplicate_id_validation": "performed by the index builder"}
        metadata(stage, report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("data/wiki"))
    parser.add_argument("--cache", type=Path, default=Path("data/_downloads"))
    parser.add_argument("--source", type=Path, help="Use an already downloaded original Wiki JSONL")
    args = parser.parse_args()
    if args.output.exists() or args.output.is_symlink():
        parser.error(f"Output already exists: {args.output}")
    source = args.source or hub_file(REPOSITORY, REVISION, FILENAME, args.cache)
    info = ({"local": str(args.source)} if args.source else
            {"repository": REPOSITORY, "revision": REVISION, "file": FILENAME})
    report = prepare(source, args.output, info, expected=21015324)
    print(f"Prepared {report['documents']} documents in {args.output}")


if __name__ == "__main__":
    main()
