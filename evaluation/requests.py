"""Strict log reading and readable request text, excluding provider framing."""

from collections import OrderedDict
from dataclasses import dataclass
import json


class AccountingError(ValueError):
    pass


def strict_json(value, label):
    def unique(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                raise AccountingError(f"{label}: duplicate JSON key {key!r}")
            result[key] = item
        return result

    def finite(value):
        raise AccountingError(f"{label}: invalid JSON number {value}")

    try:
        return json.loads(value, object_pairs_hook=unique, parse_constant=finite)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AccountingError(f"{label}: invalid UTF-8/JSON") from error


def rows(path):
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            value = strict_json(line, f"{path}:{line_number}")
            if not isinstance(value, dict):
                raise AccountingError(f"{path}:{line_number}: expected an object")
            yield value


def document(path):
    value = strict_json(path.read_bytes(), str(path))
    if not isinstance(value, dict):
        raise AccountingError(f"{path}: expected an object")
    return value


class TokenCounter:
    """Count each readable field separately; keep a bounded cache of repeated fields."""

    def __init__(self):
        import tiktoken
        self.encoding = tiktoken.get_encoding("o200k_base")
        self.cache = OrderedDict()
        self.cached_chars = 0

    def __call__(self, text):
        if text in self.cache:
            self.cache.move_to_end(text)
            return self.cache[text]
        count = len(self.encoding.encode(text, disallowed_special=()))
        if len(text) <= 65536:
            self.cache[text] = count
            self.cached_chars += len(text)
            while self.cached_chars > 2_000_000 or len(self.cache) > 512:
                key, _ = self.cache.popitem(last=False)
                self.cached_chars -= len(key)
        return count


@dataclass(frozen=True)
class Piece:
    text: str
    role: str
    kind: str = "text"
    call_id: str | None = None


def readable(value):
    """Known text-only OpenAI content forms. Unknown forms are not silently skipped."""
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    if not isinstance(value, list):
        raise AccountingError("Expected text or a list of readable content blocks")
    result = []
    for block in value:
        if not isinstance(block, dict):
            raise AccountingError("Invalid content block")
        kind = block.get("type")
        field = "refusal" if kind == "refusal" else "text"
        if kind not in {"text", "input_text", "output_text", "summary_text", "reasoning_text", "refusal"}:
            raise AccountingError(f"Unsupported content block: {kind}; no context estimate substituted")
        if not isinstance(block.get(field), str):
            raise AccountingError(f"Missing readable text: {kind}")
        result.append(block[field])
    return result


def request_pieces(payload):
    if not isinstance(payload, dict):
        raise AccountingError("Missing provider payload")
    if payload.get("previous_response_id") or payload.get("conversation"):
        raise AccountingError("Server-side history is not fully recorded")
    if ("input" in payload) == ("messages" in payload):
        raise AccountingError("Expected exactly one of Responses input or Chat messages")
    if payload.get("instructions") is not None:
        for item in readable(payload["instructions"]):
            yield Piece(item, "developer")
    entries = payload.get("input", payload.get("messages"))
    if isinstance(entries, str):
        entries = [{"role": "user", "content": entries}]
    if not isinstance(entries, list):
        raise AccountingError("Request history is not a list")
    for entry in entries:
        if not isinstance(entry, dict):
            raise AccountingError("Invalid request history item")
        kind = entry.get("type", "message")
        if kind == "reasoning":
            # Encrypted content, IDs, signatures and framing are not readable text.
            for field in ("summary", "content"):
                for item in readable(entry.get(field)):
                    yield Piece(item, "assistant", "reasoning")
        elif kind == "function_call":
            for field in ("name", "arguments"):
                if not isinstance(entry.get(field), str):
                    raise AccountingError(f"Function call {field} must be recorded text")
                yield Piece(entry[field], "assistant", "tool_call")
        elif kind == "function_call_output":
            call_id = entry.get("call_id")
            if not isinstance(call_id, str) or not call_id:
                raise AccountingError("Function output is missing call_id")
            for item in readable(entry.get("output")):
                yield Piece(item, "tool", "tool_output", call_id)
        elif kind == "message":
            role = entry.get("role")
            if role not in {"system", "developer", "user", "assistant", "tool"}:
                raise AccountingError(f"Unsupported message role: {role}")
            call_id = entry.get("tool_call_id") if role == "tool" else None
            if role == "tool" and (not isinstance(call_id, str) or not call_id):
                raise AccountingError("Tool output is missing tool_call_id")
            for item in readable(entry.get("content")):
                yield Piece(item, role, "tool_output" if role == "tool" else "text", call_id)
            for field in ("reasoning_content", "reasoning"):
                if field in entry and entry[field] is not None:
                    for item in readable(entry[field]):
                        yield Piece(item, role, "reasoning")
            for call in entry.get("tool_calls", []):
                function = call.get("function", {})
                if call.get("type") != "function":
                    raise AccountingError("Unsupported assistant tool call")
                for field in ("name", "arguments"):
                    if not isinstance(function.get(field), str):
                        raise AccountingError(f"Missing function {field}")
                    yield Piece(function[field], role, "tool_call")
        else:
            raise AccountingError(f"Unsupported request item: {kind}")


def dynamic_pieces(payload, system_prompt, user_prompt, *, first=False):
    """Exclude one exact fixed prefix per request; retain dynamic suffixes and ledgers."""
    seen_system = seen_user = False
    result = []
    for piece in request_pieces(payload):
        text = piece.text
        if piece.role in {"system", "developer"} and not seen_system and system_prompt and text.startswith(system_prompt):
            text = text[len(system_prompt):]
            seen_system = True
        elif piece.role == "user" and not seen_user and user_prompt and text.startswith(user_prompt):
            text = text[len(user_prompt):]
            seen_user = True
        if text:
            result.append(Piece(text, piece.role, piece.kind, piece.call_id))
    if first and ((system_prompt and not seen_system) or (user_prompt and not seen_user)):
        raise AccountingError("First request does not match the saved fixed system/user prompts")
    return result

