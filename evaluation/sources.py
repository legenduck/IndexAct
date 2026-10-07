"""Validate structured source passages against a bounded, disk-backed corpus lookup."""

from collections import OrderedDict
import json
import sqlite3

from .requests import AccountingError, rows


def read_qrels(path):
    result = {}
    seen = set()
    with path.open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            fields = line.split()
            if len(fields) != 4:
                raise AccountingError(f"{path}:{number}: expected four qrel fields")
            qid, _, doc_key, relevance = fields
            try:
                relevance = int(relevance)
            except ValueError as error:
                raise AccountingError(f"{path}:{number}: noninteger relevance") from error
            if (qid, doc_key) in seen:
                raise AccountingError(f"{path}:{number}: duplicate qrel pair")
            seen.add((qid, doc_key))
            result.setdefault(qid, set())
            if relevance > 0:
                result[qid].add(doc_key)
    return result


def evidence_records(observation):
    if not isinstance(observation, dict) or observation.get("status") != "success":
        return []
    value = observation.get("value")
    if not isinstance(value, dict):
        return []
    records = value.get("evidence", [])
    if not isinstance(records, list) or any(not isinstance(r, dict) for r in records):
        raise AccountingError("Malformed structured evidence list")
    return records


def source_identity(record):
    key, start, end, text = (record.get(f) for f in ("doc_key", "start", "end", "text"))
    if not isinstance(key, str) or not key or not isinstance(text, str):
        raise AccountingError("Source record needs doc_key and exact text")
    if type(start) is not int or type(end) is not int or start < 0 or end < start or end - start != len(text):
        raise AccountingError(f"Invalid codepoint offsets for document {key}")
    return key, start, end, text


class Corpus:
    """Scan JSONL once; retain only referenced documents on disk, not the whole corpus."""

    def __init__(self, path, wanted, database):
        if not path.is_file():
            raise AccountingError(f"Missing canonical corpus: {path}")
        self.connection = sqlite3.connect(database)
        self.cache = OrderedDict()
        self.cache_chars = 0
        try:
            self.connection.execute("CREATE TABLE documents (doc_key TEXT PRIMARY KEY, body TEXT NOT NULL)")
            self._load(path, wanted)
        except Exception:
            self.connection.close()
            raise

    def _load(self, path, wanted):
        remaining = set(wanted)
        if not wanted:
            return
        for row in rows(path):
            key = row.get("doc_key")
            if not isinstance(key, str) or not key:
                raise AccountingError("Canonical corpus records require a nonempty string doc_key")
            if key not in wanted:
                continue
            body = row.get("raw_text")
            if not isinstance(body, str):
                raise AccountingError(f"Corpus document {key} has no raw_text")
            try:
                self.connection.execute("INSERT INTO documents VALUES (?, ?)", (key, body))
            except sqlite3.IntegrityError as error:
                raise AccountingError(f"Duplicate referenced corpus document: {key}") from error
            remaining.discard(key)
        self.connection.commit()
        if remaining:
            raise AccountingError(f"Canonical corpus is missing {len(remaining)} referenced documents: {sorted(remaining)[:5]}")

    def close(self):
        self.connection.close()

    def body(self, key):
        if key in self.cache:
            self.cache.move_to_end(key)
            return self.cache[key]
        row = self.connection.execute("SELECT body FROM documents WHERE doc_key = ?", (key,)).fetchone()
        if row is None:
            raise AccountingError(f"Source document was not in the recorded tool deliveries: {key}")
        body = row[0]
        if len(body) <= 1_000_000:
            self.cache[key] = body
            self.cache_chars += len(body)
            while self.cache_chars > 4_000_000 or len(self.cache) > 32:
                _, old_body = self.cache.popitem(last=False)
                self.cache_chars -= len(old_body)
        return body

    def verify(self, record):
        key, start, end, text = source_identity(record)
        body = self.body(key)
        if end > len(body) or body[start:end] != text:
            raise AccountingError(f"Source text does not match canonical document {key} at [{start}, {end})")
        return key, start, end, text


class Deliveries:
    """Link actual model-input passages to their recorded tool executions."""

    def __init__(self, path, corpus):
        self.by_call = {}
        for call in rows(path):
            call_id = call.get("call_id")
            if not isinstance(call_id, str) or not call_id:
                raise AccountingError("Tool execution has no call_id")
            # Pi Responses drops the item-id suffix, but retains the call-id prefix.
            normalized = call_id.split("|", 1)[0]
            if normalized in self.by_call:
                raise AccountingError(f"Duplicate/ambiguous tool execution ID: {normalized}")
            if "observation" not in call:
                raise AccountingError("Tool log lacks its model-visible observation")
            returned = set()
            for record in evidence_records(call["observation"]):
                identity = corpus.verify(record)
                returned.add(identity)
            self.by_call[normalized] = returned

    def observed(self, pieces, corpus):
        observed = set()
        for piece in pieces:
            if piece.kind != "tool_output":
                continue
            normalized = piece.call_id.split("|", 1)[0]
            if normalized not in self.by_call:
                # Pi can return blocked/invalid-tool errors without invoking the bridge.
                known = None
            else:
                known = self.by_call[normalized]
            try:
                value = json.loads(piece.text)
            except (ValueError, TypeError):
                # Cleared/non-source/error text is not source exposure.
                continue
            for record in evidence_records(value):
                identity = corpus.verify(record)
                if known is None or identity not in known:
                    raise AccountingError(f"Request contains source text absent from tool execution {normalized}")
                if identity[3]:
                    observed.add(identity[0])
        return observed
