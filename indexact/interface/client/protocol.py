"""Strict IndexAct protocol models, response decoder, and HTTP transport.

The decoder is intentionally closed-world: every response object is checked for
its exact required/optional fields and unknown result variants are rejected.
Top-level symbolic errors become :class:`ProtocolError`; operation errors remain
inside :class:`ExecuteTrace` as required by the wire contract.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum
import json
import math
import re
import secrets
import socket
import threading
import time
from typing import Any, Callable, ClassVar, Mapping, Protocol, Sequence, TypeVar
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from .dsl import (
    Count,
    CountDocs,
    DocKeyTarget,
    HandleRef,
    JSONValue,
    Operation,
    Page,
    Read,
    ReadBudget,
    StateTarget,
    WireObject,
    copy_json,
    validate_lineage_string,
    validate_non_negative_int,
    validate_positive_int,
    validate_raw_text,
    validate_state_handle,
)


SUPPORTED_PROTOCOL_VERSION = "3.4"

_PROTOCOL_VERSION_RE = re.compile(r"[1-9][0-9]*\.[0-9]+\Z")
_SNAPSHOT_ID_RE = re.compile(r"snap-[0-9a-f]{64}\Z")
_SESSION_ID_RE = re.compile(r"sess-[0-9a-f]{32}\Z")
_READ_CURSOR_RE = re.compile(r"cur-[0-9a-f]{64}\Z")
_STATE_CURSOR_RE = re.compile(r"stc-[0-9a-f]{64}\Z")
_LINEAGE_ID_RE = re.compile(r"lin-[0-9a-f]{64}\Z")


class ClientError(Exception):
    """Base class for typed-client failures."""


class WireDecodeError(ClientError):
    """The peer returned a response outside the negotiated protocol schema."""


class ResponseLostError(ClientError):
    """No complete response was received from the transport."""

    def __init__(self, message: str = "response was not received") -> None:
        super().__init__(message)


class UnknownOutcomeError(ClientError):
    """A non-idempotent request may or may not have committed server-side."""

    def __init__(self, operation: str, cause: BaseException) -> None:
        self.operation = operation
        self.cause = cause
        super().__init__(f"{operation} outcome is UNKNOWN after response loss")


class ErrorCode(str, Enum):
    MALFORMED_REQUEST = "MALFORMED_REQUEST"
    SESSION_NOT_FOUND = "SESSION_NOT_FOUND"
    SNAPSHOT_NOT_FOUND = "SNAPSHOT_NOT_FOUND"
    SNAPSHOT_INCOMPATIBLE = "SNAPSHOT_INCOMPATIBLE"
    UNSUPPORTED_PROTOCOL_VERSION = "UNSUPPORTED_PROTOCOL_VERSION"
    LINEAGE_NOT_FOUND = "LINEAGE_NOT_FOUND"
    INVALID_STATE_REF = "INVALID_STATE_REF"
    DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND"
    MALFORMED_OPERATION = "MALFORMED_OPERATION"
    TYPE_MISMATCH = "TYPE_MISMATCH"
    INVALID_ARGUMENT = "INVALID_ARGUMENT"
    RESOURCE_LIMIT_EXCEEDED = "RESOURCE_LIMIT_EXCEEDED"
    INTERNAL_ERROR = "INTERNAL_ERROR"


ERROR_REGISTRY = frozenset(code.value for code in ErrorCode)


@dataclass(frozen=True, slots=True)
class OperationError:
    code: ErrorCode | str
    message: str
    details: JSONValue | None = None
    has_details: bool = False

    @property
    def is_known(self) -> bool:
        return isinstance(self.code, ErrorCode)


class ProtocolError(ClientError):
    """A declared top-level protocol error response."""

    def __init__(self, error: OperationError) -> None:
        self.error = error
        super().__init__(f"{error.code}: {error.message}")


class StateType(str, Enum):
    SET = "set"
    RANKED = "ranked"


class StateCreator(str, Enum):
    CORPUS = "CORPUS"
    FILTER = "FILTER"
    INTERSECT = "INTERSECT"
    UNION = "UNION"
    DIFFERENCE = "DIFFERENCE"
    RANK = "RANK"
    TOPK = "TOPK"
    RESTRICT = "RESTRICT"
    AS_SET = "AS_SET"


@dataclass(frozen=True, slots=True)
class StateMetadata:
    handle: str
    state_type: StateType
    snapshot_id: str
    cardinality: int
    lineage_id: str
    created_by: StateCreator

    @property
    def ref(self) -> HandleRef:
        """Return the typed state reference used as an input to later operations."""

        return HandleRef(self.handle)


@dataclass(frozen=True, slots=True)
class ServiceLimits:
    max_request_bytes: int
    max_response_bytes: int
    max_batch_operations: int
    max_ast_nodes: int
    max_ast_depth: int
    max_gap_choice_branches: int
    max_page_limit: int
    max_active_nonroot_states: int
    max_doc_key_utf8_bytes: int
    max_lineage_string_utf8_bytes: int
    max_analyzed_term_utf8_bytes: int
    max_read_selected_docs: int
    max_read_output_codepoints: int
    max_read_evidence_count: int
    max_lineage_node_bytes: int
    max_retained_lineage_nodes: int
    max_retained_lineage_bytes: int
    max_lineage_depth: int


@dataclass(frozen=True, slots=True)
class OpenSessionResult:
    protocol_version: str
    session_id: str
    corpus: StateMetadata
    service_limits: ServiceLimits


@dataclass(frozen=True, slots=True)
class CloseSessionResult:
    closed: bool


@dataclass(frozen=True, slots=True)
class ReleaseStateResult:
    released: bool


@dataclass(frozen=True, slots=True)
class StateListPage:
    states: tuple[StateMetadata, ...]
    next_cursor: str | None = None


@dataclass(frozen=True, slots=True)
class CanonicalLineageNode:
    id: str
    op: StateCreator
    parents: tuple[str, ...]
    args: JSONValue


@dataclass(frozen=True, slots=True)
class PageSelection:
    doc_keys: tuple[str, ...]
    next_cursor: str | None = None


@dataclass(frozen=True, slots=True)
class Evidence:
    snapshot_id: str
    doc_key: str
    start: int
    end: int
    text: str


class OperationResult:
    pass


@dataclass(frozen=True, slots=True)
class StateCreated(OperationResult):
    state: StateMetadata


@dataclass(frozen=True, slots=True)
class CountResult(OperationResult):
    count: int


@dataclass(frozen=True, slots=True)
class ReadSuccess(OperationResult):
    target_docs: int
    selected_docs: int
    region_docs: int
    evidence_count: int
    required_output_codepoints: int
    evidence: tuple[Evidence, ...]
    page: PageSelection | None = None
    status: ClassVar[str] = "success"


class ExceededLimit(str, Enum):
    OUTPUT_CODEPOINTS = "output_codepoints"
    EVIDENCE_COUNT = "evidence_count"


@dataclass(frozen=True, slots=True)
class ReadBudgetExceeded(OperationResult):
    budget: ReadBudget
    exceeded_limits: tuple[ExceededLimit, ...]
    target_docs: int
    selected_docs: int
    evaluation_complete: bool
    observed_output_codepoints_lower_bound: int
    evaluated_docs_count: int
    region_docs_lower_bound: int
    evidence_count_lower_bound: int
    page: PageSelection | None = None
    required_output_codepoints: int | None = None
    region_docs: int | None = None
    evidence_count: int | None = None
    status: ClassVar[str] = "budget_exceeded"


@dataclass(frozen=True, slots=True)
class CompletedOperation:
    index: int
    result: OperationResult


@dataclass(frozen=True, slots=True)
class ExecuteTrace:
    completed: tuple[CompletedOperation, ...]
    failed_operation_index: int | None = None
    error: OperationError | None = None

    @property
    def succeeded(self) -> bool:
        return self.error is None


def _identifier(value: object, pattern: re.Pattern[str], field: str) -> str:
    value = validate_lineage_string(value, field)
    if pattern.fullmatch(value) is None:
        raise ValueError(f"{field} has invalid grammar")
    return value


def validate_protocol_version(value: object) -> str:
    return _identifier(value, _PROTOCOL_VERSION_RE, "protocol_version")


def validate_snapshot_id(value: object) -> str:
    return _identifier(value, _SNAPSHOT_ID_RE, "snapshot_id")


def validate_session_id(value: object) -> str:
    return _identifier(value, _SESSION_ID_RE, "session_id")


def validate_read_cursor(value: object) -> str:
    return _identifier(value, _READ_CURSOR_RE, "ReadCursor")


def validate_state_list_cursor(value: object) -> str:
    return _identifier(value, _STATE_CURSOR_RE, "StateListCursor")


def validate_lineage_id(value: object) -> str:
    return _identifier(value, _LINEAGE_ID_RE, "lineage_id")


class RequestModel:
    op: ClassVar[str]

    def to_wire(self) -> WireObject:
        raise NotImplementedError


@dataclass(frozen=True, slots=True)
class OpenSessionRequest(RequestModel):
    op: ClassVar[str] = "OPEN_SESSION"
    snapshot_id: str
    protocol_version: str = SUPPORTED_PROTOCOL_VERSION

    def __post_init__(self) -> None:
        validate_protocol_version(self.protocol_version)
        validate_snapshot_id(self.snapshot_id)

    def to_wire(self) -> WireObject:
        return {
            "op": self.op,
            "protocol_version": self.protocol_version,
            "snapshot_id": self.snapshot_id,
        }


@dataclass(frozen=True, slots=True)
class CloseSessionRequest(RequestModel):
    op: ClassVar[str] = "CLOSE_SESSION"
    session_id: str

    def __post_init__(self) -> None:
        validate_session_id(self.session_id)

    def to_wire(self) -> WireObject:
        return {"op": self.op, "session_id": self.session_id}


@dataclass(frozen=True, slots=True)
class ListStatesRequest(RequestModel):
    op: ClassVar[str] = "LIST_STATES"
    session_id: str
    limit: int
    after: str | None = None

    def __post_init__(self) -> None:
        validate_session_id(self.session_id)
        validate_positive_int(self.limit, "limit")
        if self.after is not None:
            validate_state_list_cursor(self.after)

    def to_wire(self) -> WireObject:
        wire: WireObject = {"op": self.op, "session_id": self.session_id, "limit": self.limit}
        if self.after is not None:
            wire["after"] = self.after
        return wire


@dataclass(frozen=True, slots=True)
class GetLineageNodeRequest(RequestModel):
    op: ClassVar[str] = "GET_LINEAGE_NODE"
    session_id: str
    lineage_id: str

    def __post_init__(self) -> None:
        validate_session_id(self.session_id)
        validate_lineage_id(self.lineage_id)

    def to_wire(self) -> WireObject:
        return {"op": self.op, "session_id": self.session_id, "lineage_id": self.lineage_id}


@dataclass(frozen=True, slots=True)
class ReleaseStateRequest(RequestModel):
    op: ClassVar[str] = "RELEASE_STATE"
    session_id: str
    handle: str

    def __post_init__(self) -> None:
        validate_session_id(self.session_id)
        validate_state_handle(self.handle)

    def to_wire(self) -> WireObject:
        return {"op": self.op, "session_id": self.session_id, "handle": self.handle}


@dataclass(frozen=True, slots=True)
class ExecuteRequest(RequestModel):
    op: ClassVar[str] = "EXECUTE"
    session_id: str
    ops: tuple[Operation, ...]

    def __post_init__(self) -> None:
        validate_session_id(self.session_id)
        if isinstance(self.ops, (str, bytes)) or not isinstance(self.ops, Sequence):
            raise TypeError("ops must be a sequence")
        ops = tuple(self.ops)
        if not all(isinstance(operation, Operation) for operation in ops):
            raise TypeError("every ops item must be an Operation")
        object.__setattr__(self, "ops", ops)

    def to_wire(self) -> WireObject:
        return {"op": self.op, "session_id": self.session_id, "ops": [op.to_wire() for op in self.ops]}


def compact_json_bytes(value: Mapping[str, JSONValue]) -> bytes:
    """Encode a request as UTF-8 compact JSON without ASCII escaping."""

    try:
        pieces: list[str] = []
        pending: list[tuple[str, object]] = [("value", value)]
        while pending:
            action, current = pending.pop()
            if action == "text":
                pieces.append(current)  # type: ignore[arg-type]
            elif isinstance(current, Mapping):
                items = list(current.items())
                pending.append(("text", "}"))
                for index in range(len(items) - 1, -1, -1):
                    key, item = items[index]
                    if not isinstance(key, str):
                        raise TypeError("JSON object keys must be strings")
                    if index + 1 < len(items):
                        pending.append(("text", ","))
                    pending.append(("value", item))
                    pending.append(("text", ":"))
                    pending.append(
                        (
                            "text",
                            json.dumps(
                                key,
                                ensure_ascii=False,
                                separators=(",", ":"),
                            ),
                        )
                    )
                pending.append(("text", "{"))
            elif isinstance(current, (list, tuple)):
                pending.append(("text", "]"))
                for index in range(len(current) - 1, -1, -1):
                    if index + 1 < len(current):
                        pending.append(("text", ","))
                    pending.append(("value", current[index]))
                pending.append(("text", "["))
            else:
                pieces.append(
                    json.dumps(
                        current,
                        ensure_ascii=False,
                        allow_nan=False,
                        separators=(",", ":"),
                    )
                )
        return "".join(pieces).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError) as error:
        raise ClientError(f"request is not valid UTF-8 JSON: {error}") from error


def _ecmascript_number(value: float) -> str:
    if not math.isfinite(value):
        raise WireDecodeError("response JSON number must be finite")
    if value == 0.0:
        return "0"
    sign = "-" if value < 0 else ""
    decimal_tuple = Decimal(repr(abs(value))).as_tuple()
    digits = "".join(str(digit) for digit in decimal_tuple.digits)
    exponent = decimal_tuple.exponent
    while len(digits) > 1 and digits.endswith("0"):
        digits = digits[:-1]
        exponent += 1
    digit_count = len(digits)
    decimal_point = digit_count + exponent
    if digit_count <= decimal_point <= 21:
        body = digits + "0" * (decimal_point - digit_count)
    elif 0 < decimal_point <= 21:
        body = digits[:decimal_point] + "." + digits[decimal_point:]
    elif -6 < decimal_point <= 0:
        body = "0." + "0" * -decimal_point + digits
    else:
        scientific_exponent = decimal_point - 1
        coefficient = digits[0]
        if digit_count > 1:
            coefficient += "." + digits[1:]
        exponent_sign = "+" if scientific_exponent >= 0 else ""
        body = f"{coefficient}e{exponent_sign}{scientific_exponent}"
    return sign + body


def _compact_response_value(value: JSONValue) -> str:
    pieces: list[str] = []
    pending: list[tuple[str, object]] = [("value", value)]
    while pending:
        action, current = pending.pop()
        if action == "text":
            pieces.append(current)  # type: ignore[arg-type]
        elif current is None:
            pieces.append("null")
        elif current is True:
            pieces.append("true")
        elif current is False:
            pieces.append("false")
        elif isinstance(current, str):
            pieces.append(json.dumps(current, ensure_ascii=False, separators=(",", ":")))
        elif isinstance(current, int):
            pieces.append(str(current))
        elif isinstance(current, float):
            pieces.append(_ecmascript_number(current))
        elif isinstance(current, list):
            pending.append(("text", "]"))
            for index in range(len(current) - 1, -1, -1):
                if index + 1 < len(current):
                    pending.append(("text", ","))
                pending.append(("value", current[index]))
            pending.append(("text", "["))
        elif isinstance(current, Mapping):
            items = list(current.items())
            pending.append(("text", "}"))
            for index in range(len(items) - 1, -1, -1):
                key, item = items[index]
                if index + 1 < len(items):
                    pending.append(("text", ","))
                pending.append(("value", item))
                pending.append(("text", ":"))
                pending.append(
                    (
                        "text",
                        json.dumps(key, ensure_ascii=False, separators=(",", ":")),
                    )
                )
            pending.append(("text", "{"))
        else:
            raise WireDecodeError("error.details is not JSON-compatible")
    return "".join(pieces)


class _ClientJsonError(ValueError):
    pass


class _IterativeJsonDecoder:
    _OBJECT_FIRST = 0
    _OBJECT_KEY = 1
    _OBJECT_VALUE = 2
    _OBJECT_AFTER = 3
    _ARRAY_FIRST = 4
    _ARRAY_VALUE = 5
    _ARRAY_AFTER = 6

    def __init__(self, source: str) -> None:
        self.source = source
        self.cursor = 0
        self.stack: list[list[Any]] = []
        self.result: Any = None
        self.has_result = False

    def parse(self) -> Any:
        self._begin_value()
        while self.stack:
            frame = self.stack[-1]
            self._whitespace()
            state = frame[1]
            if state == self._OBJECT_FIRST:
                if self._consume("}"):
                    self.stack.pop()
                else:
                    frame[1] = self._OBJECT_KEY
            elif state == self._OBJECT_KEY:
                if self._peek() != '"':
                    raise _ClientJsonError("object property name must be a string")
                key = self._string()
                if key in frame[0]:
                    raise _ClientJsonError(f"duplicate object field {key!r}")
                self._whitespace()
                self._require(":")
                frame[2] = key
                frame[1] = self._OBJECT_VALUE
            elif state == self._OBJECT_VALUE:
                self._begin_value()
            elif state == self._OBJECT_AFTER:
                if self._consume("}"):
                    self.stack.pop()
                else:
                    self._require(",")
                    frame[1] = self._OBJECT_KEY
            elif state == self._ARRAY_FIRST:
                if self._consume("]"):
                    self.stack.pop()
                else:
                    frame[1] = self._ARRAY_VALUE
            elif state == self._ARRAY_VALUE:
                self._begin_value()
            else:
                if self._consume("]"):
                    self.stack.pop()
                else:
                    self._require(",")
                    frame[1] = self._ARRAY_VALUE
        self._whitespace()
        if self.cursor != len(self.source):
            raise _ClientJsonError("trailing data after JSON value")
        return self.result

    def _begin_value(self) -> None:
        self._whitespace()
        char = self._peek()
        if char == "{":
            self.cursor += 1
            value: Any = {}
            self._accept(value)
            self.stack.append([value, self._OBJECT_FIRST, None])
        elif char == "[":
            self.cursor += 1
            value = []
            self._accept(value)
            self.stack.append([value, self._ARRAY_FIRST, None])
        elif char == '"':
            self._accept(self._string())
        elif self.source.startswith("true", self.cursor):
            self.cursor += 4
            self._accept(True)
        elif self.source.startswith("false", self.cursor):
            self.cursor += 5
            self._accept(False)
        elif self.source.startswith("null", self.cursor):
            self.cursor += 4
            self._accept(None)
        else:
            self._accept(self._number())

    def _accept(self, value: Any) -> None:
        if not self.stack:
            if self.has_result:
                raise _ClientJsonError("multiple top-level values")
            self.result = value
            self.has_result = True
            return
        frame = self.stack[-1]
        if frame[1] == self._OBJECT_VALUE:
            frame[0][frame[2]] = value
            frame[2] = None
            frame[1] = self._OBJECT_AFTER
        elif frame[1] == self._ARRAY_VALUE:
            frame[0].append(value)
            frame[1] = self._ARRAY_AFTER
        else:  # pragma: no cover
            raise AssertionError("JSON value in invalid decoder state")

    def _string(self) -> str:
        self._require('"')
        pieces: list[str] = []
        start = self.cursor
        while self.cursor < len(self.source):
            char = self.source[self.cursor]
            self.cursor += 1
            if char == '"':
                pieces.append(self.source[start : self.cursor - 1])
                return "".join(pieces)
            if ord(char) < 0x20:
                raise _ClientJsonError("unescaped control character")
            if char != "\\":
                continue
            pieces.append(self.source[start : self.cursor - 1])
            if self.cursor >= len(self.source):
                raise _ClientJsonError("unterminated string escape")
            escaped = self.source[self.cursor]
            self.cursor += 1
            simple = {
                '"': '"',
                "\\": "\\",
                "/": "/",
                "b": "\b",
                "f": "\f",
                "n": "\n",
                "r": "\r",
                "t": "\t",
            }
            if escaped in simple:
                pieces.append(simple[escaped])
            elif escaped == "u":
                codepoint = self._hex_quad()
                if (
                    0xD800 <= codepoint <= 0xDBFF
                    and self.source.startswith("\\u", self.cursor)
                    and self.cursor + 6 <= len(self.source)
                ):
                    saved = self.cursor
                    self.cursor += 2
                    low = self._hex_quad()
                    if 0xDC00 <= low <= 0xDFFF:
                        codepoint = (
                            0x10000
                            + ((codepoint - 0xD800) << 10)
                            + low
                            - 0xDC00
                        )
                    else:
                        self.cursor = saved
                pieces.append(chr(codepoint))
            else:
                raise _ClientJsonError("invalid string escape")
            start = self.cursor
        raise _ClientJsonError("unterminated string")

    def _hex_quad(self) -> int:
        end = self.cursor + 4
        if end > len(self.source):
            raise _ClientJsonError("short Unicode escape")
        spelling = self.source[self.cursor : end]
        if any(char not in "0123456789abcdefABCDEF" for char in spelling):
            raise _ClientJsonError("invalid Unicode escape")
        self.cursor = end
        return int(spelling, 16)

    def _number(self) -> int | float:
        match = re.match(
            r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?",
            self.source[self.cursor :],
        )
        if match is None:
            raise _ClientJsonError("expected a JSON value")
        spelling = match.group(0)
        self.cursor += len(spelling)
        if any(char in spelling for char in ".eE"):
            value = float(spelling)
            if not math.isfinite(value):
                raise _ClientJsonError("non-finite decoded JSON number")
            return value
        return int(spelling)

    def _whitespace(self) -> None:
        while self.cursor < len(self.source) and self.source[self.cursor] in " \t\r\n":
            self.cursor += 1

    def _peek(self) -> str:
        if self.cursor >= len(self.source):
            raise _ClientJsonError("expected a JSON value")
        return self.source[self.cursor]

    def _consume(self, expected: str) -> bool:
        if self.cursor < len(self.source) and self.source[self.cursor] == expected:
            self.cursor += 1
            return True
        return False

    def _require(self, expected: str) -> None:
        if not self._consume(expected):
            raise _ClientJsonError(f"expected {expected!r}")


def decode_json_object(body: bytes) -> dict[str, Any]:
    try:
        text = body.decode("utf-8", errors="strict")
        value = _IterativeJsonDecoder(text).parse()
    except (UnicodeDecodeError, ValueError) as error:
        raise WireDecodeError(f"invalid response JSON: {error}") from error
    if not isinstance(value, dict):
        raise WireDecodeError("response must be a JSON object")
    return value


def _object(value: object, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise WireDecodeError(f"{field} must be an object")
    return value


def _array(value: object, field: str) -> list[Any]:
    if not isinstance(value, list):
        raise WireDecodeError(f"{field} must be an array")
    return value


def _bool(value: object, field: str) -> bool:
    if not isinstance(value, bool):
        raise WireDecodeError(f"{field} must be a bool")
    return value


def _nnint(value: object, field: str) -> int:
    try:
        return validate_non_negative_int(value, field)
    except (TypeError, ValueError) as error:
        raise WireDecodeError(str(error)) from error


def _pint(value: object, field: str) -> int:
    try:
        return validate_positive_int(value, field)
    except (TypeError, ValueError) as error:
        raise WireDecodeError(str(error)) from error


def _string(value: object, field: str, *, lineage: bool = True) -> str:
    validator = validate_lineage_string if lineage else validate_raw_text
    try:
        return validator(value, field)
    except (TypeError, ValueError) as error:
        raise WireDecodeError(str(error)) from error


def _id(value: object, validator: Callable[[object], str]) -> str:
    try:
        return validator(value)
    except (TypeError, ValueError) as error:
        raise WireDecodeError(str(error)) from error


def _fields(
    obj: Mapping[str, Any], required: set[str], optional: set[str] = frozenset(), field: str = "object"
) -> None:
    keys = set(obj)
    missing = required - keys
    unknown = keys - required - optional
    if missing:
        raise WireDecodeError(f"{field} missing field(s): {', '.join(sorted(missing))}")
    if unknown:
        raise WireDecodeError(f"{field} has unknown field(s): {', '.join(sorted(unknown))}")


def _enum(enum: type[Enum], value: object, field: str) -> Any:
    if not isinstance(value, str):
        raise WireDecodeError(f"{field} must be a string")
    try:
        return enum(value)
    except ValueError as error:
        raise WireDecodeError(f"unknown {field}: {value!r}") from error


def parse_operation_error(value: object) -> OperationError:
    obj = _object(value, "error")
    _fields(obj, {"code", "message"}, {"details"}, "error")
    code_value = _string(obj["code"], "error.code")
    try:
        code: ErrorCode | str = ErrorCode(code_value)
    except ValueError:
        # Future-version codes are generic failures, never assigned retry safety.
        code = code_value
    message = _string(obj["message"], "error.message")
    has_details = "details" in obj
    try:
        details = copy_json(obj["details"]) if has_details else None
    except (TypeError, ValueError) as error:
        raise WireDecodeError(f"invalid error.details: {error}") from error
    if details is not None and len(_compact_response_value(details).encode("utf-8")) > 4096:
        raise WireDecodeError("CompactWireJSON error.details exceeds 4096 bytes")
    return OperationError(code, message, details, has_details)


def parse_state_metadata(value: object) -> StateMetadata:
    obj = _object(value, "StateMetadata")
    _fields(
        obj,
        {"handle", "state_type", "snapshot_id", "cardinality", "lineage_id", "created_by"},
        field="StateMetadata",
    )
    try:
        handle = validate_state_handle(obj["handle"])
    except (TypeError, ValueError) as error:
        raise WireDecodeError(str(error)) from error
    metadata = StateMetadata(
        handle=handle,
        state_type=_enum(StateType, obj["state_type"], "state_type"),
        snapshot_id=_id(obj["snapshot_id"], validate_snapshot_id),
        cardinality=_nnint(obj["cardinality"], "cardinality"),
        lineage_id=_id(obj["lineage_id"], validate_lineage_id),
        created_by=_enum(StateCreator, obj["created_by"], "created_by"),
    )
    if metadata.handle == "CORPUS" and (
        metadata.state_type is not StateType.SET or metadata.created_by is not StateCreator.CORPUS
    ):
        raise WireDecodeError("CORPUS metadata must be a set created by CORPUS")
    if metadata.handle != "CORPUS" and metadata.created_by is StateCreator.CORPUS:
        raise WireDecodeError("only CORPUS may have created_by=CORPUS")
    expected_type = StateType.RANKED if metadata.handle.startswith("r") else StateType.SET
    if metadata.state_type is not expected_type:
        raise WireDecodeError("StateHandle prefix does not agree with state_type")
    ranked_creators = {StateCreator.RANK, StateCreator.TOPK, StateCreator.RESTRICT}
    if (metadata.created_by in ranked_creators) != (metadata.state_type is StateType.RANKED):
        raise WireDecodeError("created_by does not agree with state_type")
    return metadata


_LIMIT_FIELDS = tuple(ServiceLimits.__dataclass_fields__)
_NON_NEGATIVE_LIMITS = {"max_read_output_codepoints", "max_read_evidence_count"}


def parse_service_limits(value: object) -> ServiceLimits:
    obj = _object(value, "service_limits")
    _fields(obj, set(_LIMIT_FIELDS), field="service_limits")
    values = {
        name: (_nnint(obj[name], name) if name in _NON_NEGATIVE_LIMITS else _pint(obj[name], name))
        for name in _LIMIT_FIELDS
    }
    return ServiceLimits(**values)


def parse_page_selection(value: object) -> PageSelection:
    obj = _object(value, "PageSelection")
    _fields(obj, {"doc_keys"}, {"next_cursor"}, "PageSelection")
    doc_keys = tuple(_string(item, "doc_key", lineage=False) for item in _array(obj["doc_keys"], "doc_keys"))
    if len(set(doc_keys)) != len(doc_keys):
        raise WireDecodeError("PageSelection doc_keys must not contain duplicates")
    cursor = _id(obj["next_cursor"], validate_read_cursor) if "next_cursor" in obj else None
    if not doc_keys and cursor is not None:
        raise WireDecodeError("an empty PageSelection cannot have next_cursor")
    return PageSelection(doc_keys, cursor)


def parse_evidence(value: object) -> Evidence:
    obj = _object(value, "Evidence")
    _fields(obj, {"snapshot_id", "doc_key", "start", "end", "text"}, field="Evidence")
    start = _nnint(obj["start"], "Evidence.start")
    end = _nnint(obj["end"], "Evidence.end")
    text = _string(obj["text"], "Evidence.text", lineage=False)
    if start > end:
        raise WireDecodeError("Evidence.start must not exceed Evidence.end")
    if end - start != len(text):
        raise WireDecodeError("Evidence text code-point length does not equal end-start")
    return Evidence(
        _id(obj["snapshot_id"], validate_snapshot_id),
        _string(obj["doc_key"], "Evidence.doc_key", lineage=False),
        start,
        end,
        text,
    )


def parse_read_budget(value: object) -> ReadBudget:
    obj = _object(value, "ReadBudget")
    _fields(obj, {"max_output_codepoints", "max_evidence_count"}, field="ReadBudget")
    return ReadBudget(
        _nnint(obj["max_output_codepoints"], "max_output_codepoints"),
        _nnint(obj["max_evidence_count"], "max_evidence_count"),
    )


def parse_read_result(value: object) -> ReadSuccess | ReadBudgetExceeded:
    obj = _object(value, "READ result")
    status = obj.get("status")
    if status == "success":
        required = {
            "status", "target_docs", "selected_docs", "region_docs", "evidence_count",
            "required_output_codepoints", "evidence",
        }
        _fields(obj, required, {"page"}, "ReadSuccess")
        evidence = tuple(parse_evidence(item) for item in _array(obj["evidence"], "evidence"))
        result = ReadSuccess(
            target_docs=_nnint(obj["target_docs"], "target_docs"),
            selected_docs=_nnint(obj["selected_docs"], "selected_docs"),
            region_docs=_nnint(obj["region_docs"], "region_docs"),
            evidence_count=_nnint(obj["evidence_count"], "evidence_count"),
            required_output_codepoints=_nnint(
                obj["required_output_codepoints"], "required_output_codepoints"
            ),
            evidence=evidence,
            page=parse_page_selection(obj["page"]) if "page" in obj else None,
        )
        if result.evidence_count != len(result.evidence):
            raise WireDecodeError("evidence_count does not equal evidence array length")
        if result.required_output_codepoints != sum(len(item.text) for item in result.evidence):
            raise WireDecodeError("required_output_codepoints does not equal Evidence text total")
        if result.region_docs > result.selected_docs or result.selected_docs > result.target_docs:
            raise WireDecodeError("READ success counters are not monotonic")
        evidence_docs: list[str] = []
        last_by_doc: dict[str, Evidence] = {}
        closed_docs: set[str] = set()
        snapshot_ids: set[str] = set()
        for item in result.evidence:
            snapshot_ids.add(item.snapshot_id)
            if not evidence_docs or evidence_docs[-1] != item.doc_key:
                if item.doc_key in closed_docs:
                    raise WireDecodeError("Evidence for a document must be contiguous")
                if evidence_docs:
                    closed_docs.add(evidence_docs[-1])
                evidence_docs.append(item.doc_key)
            previous = last_by_doc.get(item.doc_key)
            if previous is not None and previous.end >= item.start:
                raise WireDecodeError("Evidence regions must be ordered and fully merged")
            last_by_doc[item.doc_key] = item
        if len(evidence_docs) != result.region_docs:
            raise WireDecodeError("region_docs does not equal the Evidence document count")
        if len(snapshot_ids) > 1:
            raise WireDecodeError("all Evidence entries must use one snapshot_id")
        return result
    if status == "budget_exceeded":
        required = {
            "status", "budget", "exceeded_limits", "target_docs", "selected_docs",
            "evaluation_complete", "observed_output_codepoints_lower_bound",
            "evaluated_docs_count", "region_docs_lower_bound", "evidence_count_lower_bound",
        }
        optional = {"page", "required_output_codepoints", "region_docs", "evidence_count"}
        _fields(obj, required, optional, "ReadBudgetExceeded")
        limits_array = _array(obj["exceeded_limits"], "exceeded_limits")
        limits = tuple(_enum(ExceededLimit, item, "exceeded_limits item") for item in limits_array)
        if not limits or len(set(limits)) != len(limits):
            raise WireDecodeError("exceeded_limits must be non-empty and contain no duplicates")
        complete = _bool(obj["evaluation_complete"], "evaluation_complete")
        exact_names = ("required_output_codepoints", "region_docs", "evidence_count")
        if complete and not all(name in obj for name in exact_names):
            raise WireDecodeError("complete budget result must contain all exact fields")
        result = ReadBudgetExceeded(
            budget=parse_read_budget(obj["budget"]),
            exceeded_limits=limits,
            target_docs=_nnint(obj["target_docs"], "target_docs"),
            selected_docs=_nnint(obj["selected_docs"], "selected_docs"),
            evaluation_complete=complete,
            observed_output_codepoints_lower_bound=_nnint(
                obj["observed_output_codepoints_lower_bound"],
                "observed_output_codepoints_lower_bound",
            ),
            evaluated_docs_count=_nnint(obj["evaluated_docs_count"], "evaluated_docs_count"),
            region_docs_lower_bound=_nnint(
                obj["region_docs_lower_bound"], "region_docs_lower_bound"
            ),
            evidence_count_lower_bound=_nnint(
                obj["evidence_count_lower_bound"], "evidence_count_lower_bound"
            ),
            page=parse_page_selection(obj["page"]) if "page" in obj else None,
            required_output_codepoints=(
                _nnint(obj["required_output_codepoints"], "required_output_codepoints")
                if "required_output_codepoints" in obj else None
            ),
            region_docs=(
                _nnint(obj["region_docs"], "region_docs") if "region_docs" in obj else None
            ),
            evidence_count=(
                _nnint(obj["evidence_count"], "evidence_count")
                if "evidence_count" in obj else None
            ),
        )
        if complete and (
            result.observed_output_codepoints_lower_bound != result.required_output_codepoints
            or result.region_docs_lower_bound != result.region_docs
            or result.evidence_count_lower_bound != result.evidence_count
            or result.evaluated_docs_count != result.selected_docs
        ):
            raise WireDecodeError("complete budget result exact values must equal lower bounds")
        if result.selected_docs > result.target_docs:
            raise WireDecodeError("selected_docs exceeds target_docs")
        if result.evaluated_docs_count > result.selected_docs:
            raise WireDecodeError("evaluated_docs_count exceeds selected_docs")
        if result.region_docs_lower_bound > result.evaluated_docs_count:
            raise WireDecodeError("region_docs_lower_bound exceeds evaluated_docs_count")
        if result.region_docs_lower_bound > result.evidence_count_lower_bound:
            raise WireDecodeError("region_docs_lower_bound exceeds evidence_count_lower_bound")
        if (
            result.required_output_codepoints is not None
            and result.observed_output_codepoints_lower_bound
            > result.required_output_codepoints
        ):
            raise WireDecodeError(
                "observed_output_codepoints_lower_bound exceeds required_output_codepoints"
            )
        if (
            result.region_docs is not None
            and result.region_docs_lower_bound > result.region_docs
        ):
            raise WireDecodeError("region_docs_lower_bound exceeds region_docs")
        if (
            result.evidence_count is not None
            and result.evidence_count_lower_bound > result.evidence_count
        ):
            raise WireDecodeError("evidence_count_lower_bound exceeds evidence_count")
        if result.region_docs is not None and result.region_docs > result.selected_docs:
            raise WireDecodeError("region_docs exceeds selected_docs")
        if (
            result.region_docs is not None
            and result.evidence_count is not None
            and result.region_docs > result.evidence_count
        ):
            raise WireDecodeError("region_docs exceeds evidence_count")
        proven = set()
        if (
            result.observed_output_codepoints_lower_bound
            > result.budget.max_output_codepoints
            or result.required_output_codepoints is not None
            and result.required_output_codepoints > result.budget.max_output_codepoints
        ):
            proven.add(ExceededLimit.OUTPUT_CODEPOINTS)
        if (
            result.evidence_count_lower_bound > result.budget.max_evidence_count
            or result.evidence_count is not None
            and result.evidence_count > result.budget.max_evidence_count
        ):
            proven.add(ExceededLimit.EVIDENCE_COUNT)
        if set(result.exceeded_limits) != proven:
            raise WireDecodeError("exceeded_limits does not equal the limits proven exceeded")
        return result
    raise WireDecodeError(f"unknown READ result status: {status!r}")


def parse_operation_result(value: object) -> OperationResult:
    obj = _object(value, "OperationResult")
    if "state" in obj:
        _fields(obj, {"state"}, field="StateCreated")
        return StateCreated(parse_state_metadata(obj["state"]))
    if "count" in obj:
        _fields(obj, {"count"}, field="CountResult")
        return CountResult(_nnint(obj["count"], "count"))
    if "status" in obj:
        return parse_read_result(obj)
    raise WireDecodeError("unknown OperationResult variant")


def parse_execute_trace(value: object) -> ExecuteTrace:
    obj = _object(value, "ExecuteTrace")
    _fields(obj, {"completed"}, {"failed_operation_index", "error"}, "ExecuteTrace")
    completed: list[CompletedOperation] = []
    for item in _array(obj["completed"], "completed"):
        entry = _object(item, "completed item")
        _fields(entry, {"index", "result"}, field="completed item")
        completed.append(
            CompletedOperation(_nnint(entry["index"], "completed.index"), parse_operation_result(entry["result"]))
        )
    has_index = "failed_operation_index" in obj
    has_error = "error" in obj
    if has_index != has_error:
        raise WireDecodeError("failed_operation_index and error must appear together")
    indexes = tuple(item.index for item in completed)
    if indexes != tuple(range(len(completed))):
        raise WireDecodeError("completed indexes must be the sequential success prefix")
    failed_index = (
        _nnint(obj["failed_operation_index"], "failed_operation_index") if has_index else None
    )
    # A whole-batch static preflight failure may identify any operation while
    # reporting an empty completed prefix. Once execution has begun, however,
    # a dynamic failure must immediately follow the sequential success prefix.
    if failed_index is not None and completed and failed_index != len(completed):
        raise WireDecodeError("failed_operation_index must immediately follow the completed prefix")
    return ExecuteTrace(
        tuple(completed),
        failed_index,
        parse_operation_error(obj["error"]) if has_error else None,
    )


def _validate_read_result_for_request(
    operation: Read, result: ReadSuccess | ReadBudgetExceeded
) -> None:
    expects_page = isinstance(operation.target, StateTarget) and isinstance(
        operation.documents, Page
    )
    if (result.page is not None) != expects_page:
        raise WireDecodeError("READ page presence does not agree with its DocumentSelector")
    if result.page is not None:
        if len(result.page.doc_keys) != result.selected_docs:
            raise WireDecodeError("PageSelection doc_keys length must equal selected_docs")
        assert isinstance(operation.documents, Page)
        if result.selected_docs > operation.documents.limit:
            raise WireDecodeError("PAGE selected_docs exceeds the requested limit")
        if (
            result.selected_docs < operation.documents.limit
            and result.page.next_cursor is not None
        ):
            raise WireDecodeError("a short PAGE is exhausted and cannot have next_cursor")
        if operation.documents.after is None:
            documents_remain = result.selected_docs < result.target_docs
            if documents_remain != (result.page.next_cursor is not None):
                raise WireDecodeError(
                    "initial PAGE next_cursor presence disagrees with target exhaustion"
                )
    elif isinstance(operation.target, StateTarget):
        if result.selected_docs != result.target_docs:
            raise WireDecodeError("ALL_DOCUMENTS must select the complete target")
    elif result.target_docs != 1 or result.selected_docs != 1:
        raise WireDecodeError("a successful DocKey READ must target and select one document")
    if result.region_docs is not None and result.region_docs > result.selected_docs:
        raise WireDecodeError("READ region_docs exceeds selected_docs")
    if isinstance(result, ReadSuccess):
        if result.required_output_codepoints > operation.budget.max_output_codepoints:
            raise WireDecodeError("ReadSuccess exceeds max_output_codepoints")
        if result.evidence_count > operation.budget.max_evidence_count:
            raise WireDecodeError("ReadSuccess exceeds max_evidence_count")
        evidence_doc_keys = tuple(dict.fromkeys(item.doc_key for item in result.evidence))
        if isinstance(operation.target, DocKeyTarget):
            if any(doc_key != operation.target.doc_key for doc_key in evidence_doc_keys):
                raise WireDecodeError("DocKey READ returned Evidence for another document")
        elif result.page is not None:
            positions = {doc_key: index for index, doc_key in enumerate(result.page.doc_keys)}
            try:
                evidence_positions = tuple(positions[doc_key] for doc_key in evidence_doc_keys)
            except KeyError as error:
                raise WireDecodeError("PAGE READ returned Evidence outside PageSelection") from error
            if evidence_positions != tuple(sorted(evidence_positions)):
                raise WireDecodeError("PAGE READ Evidence disagrees with PageSelection order")
    else:
        if result.budget != operation.budget:
            raise WireDecodeError("ReadBudgetExceeded budget does not echo the request budget")
        if result.evaluated_docs_count > result.selected_docs:
            raise WireDecodeError("evaluated_docs_count exceeds selected_docs")
        if result.region_docs_lower_bound > result.evaluated_docs_count:
            raise WireDecodeError("region_docs_lower_bound exceeds evaluated_docs_count")


def validate_execute_trace(request: ExecuteRequest, trace: ExecuteTrace) -> ExecuteTrace:
    """Check that a decoded trace is the typed result of the submitted batch."""

    if trace.error is None:
        if len(trace.completed) != len(request.ops):
            raise WireDecodeError("successful ExecuteTrace is not complete")
    else:
        assert trace.failed_operation_index is not None
        if trace.failed_operation_index >= len(request.ops):
            raise WireDecodeError("failed_operation_index is outside the submitted batch")
    state_ops = {
        "FILTER", "INTERSECT", "UNION", "DIFFERENCE", "RANK", "TOPK", "RESTRICT", "AS_SET"
    }
    for completed in trace.completed:
        operation = request.ops[completed.index]
        result = completed.result
        if operation.op in state_ops:
            if not isinstance(result, StateCreated):
                raise WireDecodeError(f"{operation.op} must return StateCreated")
            if result.state.created_by.value != operation.op:
                raise WireDecodeError("StateMetadata.created_by does not match the operation")
        elif isinstance(operation, (Count, CountDocs)):
            if not isinstance(result, CountResult):
                raise WireDecodeError(f"{operation.op} must return CountResult")
        elif isinstance(operation, Read):
            if not isinstance(result, (ReadSuccess, ReadBudgetExceeded)):
                raise WireDecodeError("READ must return a READ result variant")
            _validate_read_result_for_request(operation, result)
        else:  # pragma: no cover - Operation is closed by the exported request DSL.
            raise WireDecodeError(f"unsupported submitted operation {operation.op!r}")
    return trace


def parse_open_session(value: object) -> OpenSessionResult:
    obj = _object(value, "OPEN_SESSION response")
    _fields(obj, {"protocol_version", "session_id", "corpus", "service_limits"}, field="OPEN_SESSION response")
    version = _id(obj["protocol_version"], validate_protocol_version)
    if version != SUPPORTED_PROTOCOL_VERSION:
        raise WireDecodeError(f"server negotiated unsupported version {version!r}")
    result = OpenSessionResult(
        version,
        _id(obj["session_id"], validate_session_id),
        parse_state_metadata(obj["corpus"]),
        parse_service_limits(obj["service_limits"]),
    )
    if result.corpus.handle != "CORPUS":
        raise WireDecodeError("OPEN_SESSION corpus metadata must describe CORPUS")
    return result


def parse_close_session(value: object) -> CloseSessionResult:
    obj = _object(value, "CLOSE_SESSION response")
    _fields(obj, {"closed"}, field="CLOSE_SESSION response")
    closed = _bool(obj["closed"], "closed")
    if not closed:
        raise WireDecodeError("CLOSE_SESSION response must contain closed=true")
    return CloseSessionResult(closed)


def parse_release_state(value: object) -> ReleaseStateResult:
    obj = _object(value, "RELEASE_STATE response")
    _fields(obj, {"released"}, field="RELEASE_STATE response")
    return ReleaseStateResult(_bool(obj["released"], "released"))


def parse_state_list_page(value: object) -> StateListPage:
    obj = _object(value, "StateListPage")
    _fields(obj, {"states"}, {"next_cursor"}, "StateListPage")
    states = tuple(parse_state_metadata(item) for item in _array(obj["states"], "states"))
    cursor = _id(obj["next_cursor"], validate_state_list_cursor) if "next_cursor" in obj else None
    return StateListPage(states, cursor)


def _copy_lineage_json(value: JSONValue) -> JSONValue:
    work: list[tuple[JSONValue, bool]] = [(value, False)]
    built: list[JSONValue] = []
    while work:
        current, expanded = work.pop()
        if not expanded and isinstance(current, (list, dict)):
            work.append((current, True))
            children = list(current.values()) if isinstance(current, dict) else current
            work.extend((item, False) for item in reversed(children))
            continue
        if isinstance(current, str):
            try:
                built.append(validate_lineage_string(current, "lineage args string"))
            except (TypeError, ValueError) as error:
                raise WireDecodeError(str(error)) from error
        elif isinstance(current, list):
            length = len(current)
            children = built[-length:] if length else []
            if length:
                del built[-length:]
            built.append(list(children))
        elif isinstance(current, dict):
            keys = list(current.keys())
            for key in keys:
                try:
                    validate_lineage_string(key, "lineage args property")
                except (TypeError, ValueError) as error:
                    raise WireDecodeError(str(error)) from error
            children = built[-len(keys) :] if keys else []
            if keys:
                del built[-len(keys) :]
            built.append(dict(zip(keys, children)))
        else:
            try:
                built.append(copy_json(current))
            except (TypeError, ValueError) as error:
                raise WireDecodeError(f"invalid lineage args: {error}") from error
    return built[0]


def parse_lineage_node(value: object) -> CanonicalLineageNode:
    obj = _object(value, "CanonicalLineageNode")
    _fields(obj, {"id", "op", "parents", "args"}, field="CanonicalLineageNode")
    parents = tuple(
        _id(item, validate_lineage_id) for item in _array(obj["parents"], "parents")
    )
    return CanonicalLineageNode(
        _id(obj["id"], validate_lineage_id),
        _enum(StateCreator, obj["op"], "lineage op"),
        parents,
        _copy_lineage_json(obj["args"]),
    )


class Transport(Protocol):
    """Minimal injection point used by both HTTP and deterministic fake transports."""

    def send(self, request_body: bytes) -> bytes:
        """Return a complete response body or raise ResponseLostError."""


@dataclass(frozen=True, slots=True)
class HTTPRoundTrip:
    sequence: int
    request_id: str | None
    operation: str | None
    started_monotonic: float
    ended_monotonic: float
    duration_seconds: float
    outcome: str


@dataclass(slots=True)
class HTTPTransport:
    """Stdlib HTTP/JSON transport.

    ``endpoint`` is the complete service URL. This client uses it as supplied
    without appending an HTTP path.
    """

    endpoint: str
    timeout: float | None = 30.0
    clock: Callable[[], float] = field(default=time.monotonic, repr=False)
    token_factory: Callable[[], str] = field(
        default=lambda: secrets.token_hex(16), repr=False
    )
    _round_trips: list[HTTPRoundTrip] = field(default_factory=list, init=False, repr=False)

    @property
    def round_trips(self) -> tuple[HTTPRoundTrip, ...]:
        return tuple(self._round_trips)

    def send(self, request_body: bytes) -> bytes:
        request_token = self.token_factory()
        if not re.fullmatch(r"[0-9a-f]{32}", request_token):
            raise ValueError("HTTP request token must be 32 lowercase hexadecimal characters")
        request = Request(
            self.endpoint,
            data=request_body,
            method="POST",
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "Content-Encoding": "identity",
                "Accept": "application/json",
                "X-IndexAct-Request-Token": request_token,
            },
        )
        started = self.clock()
        ended: float | None = None
        outcome = "response_lost"
        try:
            with urlopen(request, timeout=self.timeout) as response:  # noqa: S310
                body = response.read()
                outcome = "completed"
                return body
        except HTTPError as error:
            # Protocol errors commonly use non-2xx HTTP statuses and still carry
            # the normative JSON error object.
            try:
                body = error.read()
                outcome = f"http_{error.code}"
                return body
            finally:
                error.close()
        except (URLError, TimeoutError, socket.timeout, OSError) as error:
            # Measure the original request only. The best-effort control request below is not
            # part of the service operation's HTTP round trip.
            ended = self.clock()
            self._cancel(request_token)
            raise ResponseLostError(str(error)) from error
        finally:
            if ended is None:
                ended = self.clock()
            self._round_trips.append(
                HTTPRoundTrip(
                    sequence=len(self._round_trips) + 1,
                    request_id=_request_id_for_timing(request_body),
                    operation=_operation_for_timing(request_body),
                    started_monotonic=started,
                    ended_monotonic=ended,
                    duration_seconds=max(0.0, ended - started),
                    outcome=outcome,
                )
            )

    def _cancel(self, request_token: str) -> None:
        request = Request(
            self.endpoint,
            data=b"",
            method="POST",
            headers={
                "Content-Encoding": "identity",
                "X-IndexAct-Cancel-Token": request_token,
            },
        )
        try:
            with urlopen(request, timeout=2.0) as response:  # noqa: S310
                response.read()
        except HTTPError as error:
            error.close()
        except (URLError, TimeoutError, socket.timeout, OSError):
            # The caller's result is already UNKNOWN. Cancellation is best effort and must
            # never replace the original transport failure with a second exception.
            return


def _request_id_for_timing(request_body: bytes) -> str | None:
    try:
        value = json.loads(request_body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(value, Mapping):
        return None
    request_id = value.get("request_id")
    return request_id if isinstance(request_id, str) else None


def _operation_for_timing(request_body: bytes) -> str | None:
    try:
        value = json.loads(request_body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(value, Mapping):
        return None
    operation = value.get("op")
    return operation if isinstance(operation, str) else None


ResultT = TypeVar("ResultT")


@dataclass(slots=True)
class ProtocolClient:
    transport: Transport
    idempotent_retries: int = 1
    _session_snapshots: dict[str, str] = field(default_factory=dict, init=False, repr=False)
    _session_limits: dict[str, ServiceLimits] = field(default_factory=dict, init=False, repr=False)
    _session_lock: threading.RLock = field(
        default_factory=threading.RLock, init=False, repr=False
    )

    def __post_init__(self) -> None:
        if isinstance(self.idempotent_retries, bool) or not isinstance(self.idempotent_retries, int):
            raise TypeError("idempotent_retries must be an integer")
        if self.idempotent_retries < 0:
            raise ValueError("idempotent_retries must be non-negative")

    def _call(
        self,
        request: RequestModel,
        parser: Callable[[object], ResultT],
        *,
        unknown_on_loss: bool,
        retryable: bool,
    ) -> ResultT:
        body = compact_json_bytes(request.to_wire())
        attempts = 1 + (self.idempotent_retries if retryable else 0)
        for attempt in range(attempts):
            try:
                response_body = self.transport.send(body)
                break
            except ResponseLostError as error:
                if unknown_on_loss:
                    # OPEN_SESSION and EXECUTE are never automatically retried.
                    raise UnknownOutcomeError(request.op, error) from error
                if attempt + 1 == attempts:
                    raise
        response = decode_json_object(response_body)
        # A failed ExecuteTrace also has an ``error`` field, but is distinguished
        # by its required ``completed`` array.  Only the one-field outer error
        # envelope is raised as ProtocolError here.
        if "error" in response and "completed" not in response:
            _fields(response, {"error"}, field="top-level error response")
            error = parse_operation_error(response["error"])
            session_id = getattr(request, "session_id", None)
            self._validate_error_diagnostics(session_id, error)
            raise ProtocolError(error)
        return parser(response)

    def open_session(
        self, snapshot_id: str, protocol_version: str = SUPPORTED_PROTOCOL_VERSION
    ) -> OpenSessionResult:
        result = self._call(
            OpenSessionRequest(snapshot_id, protocol_version),
            parse_open_session,
            unknown_on_loss=True,
            retryable=False,
        )
        if result.protocol_version != protocol_version:
            raise WireDecodeError("OPEN_SESSION response version does not match the request")
        if result.corpus.snapshot_id != snapshot_id:
            raise WireDecodeError("OPEN_SESSION corpus snapshot does not match the request")
        with self._session_lock:
            self._session_snapshots[result.session_id] = snapshot_id
            self._session_limits[result.session_id] = result.service_limits
        return result

    def close_session(self, session_id: str) -> CloseSessionResult:
        result = self._call(
            CloseSessionRequest(session_id),
            parse_close_session,
            unknown_on_loss=False,
            retryable=True,
        )
        with self._session_lock:
            self._session_snapshots.pop(session_id, None)
            self._session_limits.pop(session_id, None)
        return result

    def list_states(
        self, session_id: str, limit: int, after: str | None = None
    ) -> StateListPage:
        result = self._call(
            ListStatesRequest(session_id, limit, after),
            parse_state_list_page,
            unknown_on_loss=False,
            # Interface §14 declares automatic response-loss retries only for
            # CLOSE_SESSION and RELEASE_STATE.  Introspection is diagnostic and
            # a repeated live listing is not an exact replay of the lost read.
            retryable=False,
        )
        if len(result.states) > limit:
            raise WireDecodeError("LIST_STATES response exceeds the requested limit")
        ordinals = tuple(
            0 if state.handle == "CORPUS" else int(state.handle[1:])
            for state in result.states
        )
        if len(set(ordinals)) != len(ordinals) or any(
            left >= right for left, right in zip(ordinals, ordinals[1:])
        ):
            raise WireDecodeError("LIST_STATES states are not in creation-sequence order")
        self._validate_snapshot_identity(
            session_id, (state.snapshot_id for state in result.states)
        )
        return result

    def get_lineage_node(self, session_id: str, lineage_id: str) -> CanonicalLineageNode:
        result = self._call(
            GetLineageNodeRequest(session_id, lineage_id),
            parse_lineage_node,
            unknown_on_loss=False,
            retryable=False,
        )
        if result.id != lineage_id:
            raise WireDecodeError("GET_LINEAGE_NODE response id does not match the request")
        return result

    def release_state(self, session_id: str, handle: str) -> ReleaseStateResult:
        return self._call(
            ReleaseStateRequest(session_id, handle),
            parse_release_state,
            unknown_on_loss=False,
            retryable=True,
        )

    def execute(self, session_id: str, ops: Sequence[Operation]) -> ExecuteTrace:
        request = ExecuteRequest(session_id, tuple(ops))
        trace = self._call(
            request,
            parse_execute_trace,
            unknown_on_loss=True,
            retryable=False,
        )
        trace = validate_execute_trace(request, trace)
        if trace.error is not None:
            self._validate_error_diagnostics(session_id, trace.error)
        snapshot_ids: list[str] = []
        for completed in trace.completed:
            if isinstance(completed.result, StateCreated):
                snapshot_ids.append(completed.result.state.snapshot_id)
            elif isinstance(completed.result, ReadSuccess):
                snapshot_ids.extend(item.snapshot_id for item in completed.result.evidence)
        self._validate_snapshot_identity(session_id, snapshot_ids)
        return trace

    def _validate_snapshot_identity(
        self, session_id: str, snapshot_ids: Sequence[str]
    ) -> None:
        with self._session_lock:
            expected = self._session_snapshots.get(session_id)
        if expected is not None and any(value != expected for value in snapshot_ids):
            raise WireDecodeError("response snapshot_id disagrees with the opened session")

    def _validate_error_diagnostics(
        self, session_id: str | None, error: OperationError
    ) -> None:
        if session_id is None:
            return
        with self._session_lock:
            limits = self._session_limits.get(session_id)
        if (
            limits is not None
            and len(error.message.encode("utf-8"))
            > limits.max_lineage_string_utf8_bytes
        ):
            raise WireDecodeError(
                "error.message exceeds max_lineage_string_utf8_bytes"
            )


__all__ = [
    "ERROR_REGISTRY",
    "SUPPORTED_PROTOCOL_VERSION",
    "CanonicalLineageNode",
    "ClientError",
    "CloseSessionRequest",
    "CloseSessionResult",
    "CompletedOperation",
    "CountResult",
    "ErrorCode",
    "Evidence",
    "ExecuteRequest",
    "ExecuteTrace",
    "ExceededLimit",
    "GetLineageNodeRequest",
    "HTTPRoundTrip",
    "HTTPTransport",
    "ListStatesRequest",
    "OpenSessionRequest",
    "OpenSessionResult",
    "OperationError",
    "OperationResult",
    "PageSelection",
    "ProtocolClient",
    "ProtocolError",
    "ReadBudgetExceeded",
    "ReadSuccess",
    "ReleaseStateRequest",
    "ReleaseStateResult",
    "RequestModel",
    "ResponseLostError",
    "ServiceLimits",
    "StateCreated",
    "StateCreator",
    "StateListPage",
    "StateMetadata",
    "StateType",
    "Transport",
    "UnknownOutcomeError",
    "WireDecodeError",
    "compact_json_bytes",
    "decode_json_object",
    "parse_execute_trace",
    "parse_operation_error",
    "parse_operation_result",
    "parse_read_result",
    "validate_execute_trace",
]
