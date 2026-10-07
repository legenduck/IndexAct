"""Small I/O helpers shared by the standalone dataset preparation commands."""

from contextlib import contextmanager
import json
from pathlib import Path
import tempfile


def text(value, name, *, empty=False):
    if not isinstance(value, str) or (not empty and not value.strip()):
        raise ValueError(f"{name} must be {'a' if empty else 'a nonempty'} string")
    value.encode("utf-8", errors="strict")
    return value


def identity(value, name):
    if type(value) not in (str, int):
        raise ValueError(f"{name} must be a string or integer")
    return text(str(value), name)


def write_row(stream, row):
    stream.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")


def read_jsonl(path):
    with Path(path).open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{number}: expected a JSON object")
            yield row


def metadata(folder, value):
    (folder / "metadata.json").write_text(
        json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
        encoding="utf-8",
    )


@contextmanager
def new_directory(output):
    """Publish a complete preparation directory; never overwrite existing output."""
    output = Path(output)
    if output.exists() or output.is_symlink():
        raise FileExistsError(f"Output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f".{output.name}-", dir=output.parent) as name:
        stage = Path(name)
        yield stage
        if output.exists() or output.is_symlink():
            raise FileExistsError(f"Output appeared during preparation: {output}")
        stage.rename(output)


def hub_file(repo, revision, filename, cache):
    from huggingface_hub import hf_hub_download

    return Path(hf_hub_download(
        repo_id=repo, repo_type="dataset", revision=revision,
        filename=filename, cache_dir=str(cache),
    ))


def hub_parquets(repo, revision, split, cache):
    from huggingface_hub import HfApi

    names = sorted(name for name in HfApi().list_repo_files(
        repo_id=repo, repo_type="dataset", revision=revision,
    ) if name.startswith(f"data/{split}-") and name.endswith(".parquet"))
    if not names:
        raise ValueError(f"No {split} parquet shards in {repo}@{revision}")
    return [hub_file(repo, revision, name, cache) for name in names]


def local_parquets(path):
    path = Path(path)
    files = [path] if path.is_file() else sorted(path.rglob("*.parquet"))
    if not files:
        raise ValueError(f"No parquet files in {path}")
    return files


def parquet_rows(files, columns, *, batch_size=64):
    import pyarrow.parquet as pq

    for path in files:
        with pq.ParquetFile(path) as source:
            for batch in source.iter_batches(
                columns=columns, batch_size=batch_size, use_threads=False,
            ):
                yield from batch.to_pylist()
