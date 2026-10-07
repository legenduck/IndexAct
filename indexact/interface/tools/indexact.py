"""Structured IndexAct environment over the typed client.

This layer owns one service session and the runtime-local metadata needed to
make that session useful to an agent.  Corpus membership is never copied into
Python.  Raw Evidence text crosses this boundary only in a successful
``index_read`` :class:`~indexact.interface.types.ToolResult`.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from hashlib import sha256
import json
from typing import Any, Mapping, Protocol, Sequence

from indexact.interface.types import (
    EvidenceLedgerEntry,
    EvidenceLedgerView,
    JSONValue,
    Outcome,
    StateLedgerEntry,
    StateLedgerView,
    Task,
    ToolCall,
    ToolError,
    ToolResult,
    ToolSpec,
)
from indexact.interface.client.dsl import Operation, Page, Read
from indexact.interface.client.protocol import (
    ClientError,
    CountResult,
    ExecuteTrace,
    OperationError,
    ProtocolError,
    ReadBudgetExceeded,
    ReadSuccess,
    StateCreated,
    StateMetadata,
    UnknownOutcomeError,
)
from indexact.interface.client.tools import SessionTools
from .parsing import AgentInputError, jsonable, parse_operation, parse_operations


EXECUTE_OPERATIONS = frozenset(
    {
        "FILTER",
        "INTERSECT",
        "UNION",
        "DIFFERENCE",
        "COUNT",
        "COUNT_DOCS",
        "RANK",
        "TOPK",
        "RESTRICT",
        "AS_SET",
    }
)
STATE_OPERATIONS = frozenset({"LIST_STATES", "GET_LINEAGE_NODE", "RELEASE_STATE"})

class _Telemetry(Protocol):
    @property
    def events(self) -> tuple[object, ...]: ...

    @property
    def event_count(self) -> int: ...

    def next_request_id(self) -> str: ...

    def record_operation(
        self,
        *,
        request_id: str,
        batch_index: int | None,
        operation: str,
        request: Mapping[str, Any],
        status: str,
        result: Mapping[str, Any] | None = None,
        error: Mapping[str, Any] | None = None,
    ) -> object: ...


@dataclass(frozen=True, slots=True)
class OperationRecord:
    """One deterministic, corpus-text-free environment operation event."""

    task_id: str
    sequence: int
    request_id: str
    batch_index: int | None
    operation: str
    request: Mapping[str, JSONValue]
    status: str
    result: Mapping[str, JSONValue] | None = None
    error: Mapping[str, JSONValue] | None = None


class OperationRecorder:
    """Minimal provider-neutral recorder used when no structural sink is injected."""

    def __init__(self, task_id: str) -> None:
        self.task_id = task_id
        self._events: list[OperationRecord] = []

    @property
    def event_count(self) -> int:
        return len(self._events)

    @property
    def events(self) -> tuple[OperationRecord, ...]:
        return tuple(self._events)

    def next_request_id(self) -> str:
        seen = {event.request_id for event in self._events}
        return f"req-{len(seen) + 1:06d}"

    def record_operation(
        self,
        *,
        request_id: str,
        batch_index: int | None,
        operation: str,
        request: Mapping[str, Any],
        status: str,
        result: Mapping[str, Any] | None = None,
        error: Mapping[str, Any] | None = None,
    ) -> OperationRecord:
        event = OperationRecord(
            task_id=self.task_id,
            sequence=len(self._events),
            request_id=request_id,
            batch_index=batch_index,
            operation=operation,
            request=jsonable(request),
            status=status,
            result=jsonable(result) if result is not None else None,
            error=jsonable(error) if error is not None else None,
        )
        self._events.append(event)
        return event


def _closed_object(properties: Mapping[str, JSONValue]) -> dict[str, JSONValue]:
    return {
        "type": "object",
        "properties": dict(properties),
        "required": list(properties),
        "additionalProperties": False,
    }


def _ref(name: str) -> dict[str, JSONValue]:
    return {"$ref": f"#/$defs/{name}"}


_EMPTY = _closed_object({})
_NULLABLE_STRING: dict[str, JSONValue] = {"type": ["string", "null"]}

_STATE_REF: dict[str, JSONValue] = {
    "oneOf": [
        {"type": "string", "description": "A state handle such as CORPUS, s1, or r2."},
        _closed_object({"handle": {"type": "string"}}),
        _closed_object({"binding": {"type": "string"}}),
    ]
}
_TERM = _closed_object({"term": {"type": "string"}})
_LEXICAL: dict[str, JSONValue] = {
    "oneOf": [
        _TERM,
        _closed_object(
            {
                "any_of": {
                    "type": "array",
                    "minItems": 2,
                    "items": _ref("lexical"),
                }
            }
        ),
        _closed_object(
            {
                "phrase": {
                    "type": "array",
                    "minItems": 2,
                    "items": _ref("lexical"),
                }
            }
        ),
        _closed_object(
            {
                "near": _closed_object(
                    {
                        "children": {
                            "type": "array",
                            "minItems": 2,
                            "items": _ref("lexical"),
                        },
                        "ordered": {"type": "boolean"},
                        "max_gaps": {"type": "integer", "minimum": 0},
                    }
                )
            }
        ),
    ]
}
_CONDITION: dict[str, JSONValue] = {
    "oneOf": [
        _ref("lexical"),
        _closed_object(
            {
                "and": {
                    "type": "array",
                    "minItems": 2,
                    "items": _ref("condition"),
                }
            }
        ),
        _closed_object(
            {
                "or": {
                    "type": "array",
                    "minItems": 2,
                    "items": _ref("condition"),
                }
            }
        ),
        _closed_object({"not": _ref("condition")}),
    ]
}
_SCORING: dict[str, JSONValue] = {
    "oneOf": [
        _TERM,
        _closed_object(
            {
                "combine": {
                    "type": "array",
                    "minItems": 1,
                    "items": _TERM,
                }
            }
        ),
        _closed_object(
            {
                "weight": {
                    "type": "array",
                    "minItems": 1,
                    "items": _closed_object(
                        {
                            "weight": {"type": "number", "minimum": 0},
                            "term": {"type": "string"},
                        }
                    ),
                }
            }
        ),
    ]
}
_EXPRESSION_DEFS: dict[str, JSONValue] = {
    "state_ref": _STATE_REF,
    "lexical": _LEXICAL,
    "condition": _CONDITION,
    "scoring": _SCORING,
}


def _operation_schema(
    op: str,
    properties: Mapping[str, JSONValue],
    *,
    bind: bool = False,
) -> dict[str, JSONValue]:
    all_properties: dict[str, JSONValue] = {"op": {"enum": [op]}, **properties}
    if bind:
        all_properties["bind"] = _NULLABLE_STRING
    return _closed_object(all_properties)


_EXECUTE_OPERATION_SCHEMAS: tuple[dict[str, JSONValue], ...] = (
    _operation_schema(
        "FILTER",
        {"target": _ref("state_ref"), "condition": _ref("condition")},
        bind=True,
    ),
    *(
        _operation_schema(
            op,
            {"left": _ref("state_ref"), "right": _ref("state_ref")},
            bind=True,
        )
        for op in ("INTERSECT", "UNION", "DIFFERENCE")
    ),
    _operation_schema("COUNT", {"state": _ref("state_ref")}),
    _operation_schema(
        "COUNT_DOCS",
        {"state": _ref("state_ref"), "condition": _ref("condition")},
    ),
    _operation_schema(
        "RANK",
        {"target": _ref("state_ref"), "scoring": _ref("scoring")},
        bind=True,
    ),
    _operation_schema(
        "TOPK",
        {"target": _ref("state_ref"), "k": {"type": "integer", "minimum": 0}},
        bind=True,
    ),
    _operation_schema(
        "RESTRICT",
        {"ranked": _ref("state_ref"), "allowed": _ref("state_ref")},
        bind=True,
    ),
    _operation_schema("AS_SET", {"target": _ref("state_ref")}, bind=True),
)

_DOCUMENTS: dict[str, JSONValue] = {
    "oneOf": [
        _closed_object({"all_documents": _EMPTY}),
        _closed_object(
            {
                "page": _closed_object(
                    {
                        "limit": {"type": "integer", "minimum": 1},
                        "after": _NULLABLE_STRING,
                    }
                )
            }
        ),
    ]
}
_OCCURRENCE: dict[str, JSONValue] = {
    "oneOf": [
        _closed_object({"first": _EMPTY}),
        _closed_object({"nth": {"type": "integer", "minimum": 1}}),
        _closed_object({"all": _EMPTY}),
    ]
}
_DOCUMENT_REGION = _closed_object({"document": _EMPTY})
_RANGE_REGION = _closed_object(
    {
        "range": _closed_object(
            {
                "start": {"type": "integer", "minimum": 0},
                "end": {"type": "integer", "minimum": 0},
            }
        )
    }
)
_AROUND_REGION = _closed_object(
    {
        "around": _closed_object(
            {
                "anchor": _ref("lexical"),
                "selector": _ref("occurrence"),
                "before": {"type": "integer", "minimum": 0},
                "after": {"type": "integer", "minimum": 0},
            }
        )
    }
)
_BUDGET = _closed_object(
    {
        "max_output_codepoints": {"type": "integer", "minimum": 0},
        "max_evidence_count": {"type": "integer", "minimum": 0},
    }
)
_READ_DEFS: dict[str, JSONValue] = {
    **_EXPRESSION_DEFS,
    "documents": _DOCUMENTS,
    "occurrence": _OCCURRENCE,
    "document_region": _DOCUMENT_REGION,
    "range_region": _RANGE_REGION,
    "around_region": _AROUND_REGION,
    "region": {
        "oneOf": [
            _ref("document_region"),
            _ref("range_region"),
            _ref("around_region"),
        ]
    },
    "budget": _BUDGET,
    "target": {
        "oneOf": [
            _closed_object({"state": _ref("state_ref")}),
            _closed_object({"doc_key": {"type": "string"}}),
        ]
    },
}
_READ_PARAMETERS: dict[str, JSONValue] = {
    **_closed_object(
        {
            "target": _ref("target"),
            "documents": {"oneOf": [_ref("documents"), {"type": "null"}]},
            "region": _ref("region"),
            "budget": _ref("budget"),
        }
    ),
    "$defs": _READ_DEFS,
}

_TOOL_SPECS: tuple[ToolSpec, ...] = (
    ToolSpec(
        "index_execute",
        "Execute an ordered batch of non-text IndexAct state, count, and ranking operations.",
        {
            **_closed_object(
                {
                    "operations": {
                        "type": "array",
                        "items": _ref("operation"),
                    }
                }
            ),
            "$defs": {
                **_EXPRESSION_DEFS,
                "operation": {"oneOf": list(_EXECUTE_OPERATION_SCHEMAS)},
            },
        },
    ),
    ToolSpec(
        "index_read",
        "Read approved document, range, or occurrence regions under both semantic budgets.",
        _READ_PARAMETERS,
    ),
    ToolSpec(
        "index_state",
        "List active states, inspect a lineage node, or release a state.",
        {
            "type": "object",
            "oneOf": [
                _closed_object(
                    {
                        "op": {"type": "string", "enum": ["LIST_STATES"]},
                        "limit": {"type": "integer", "minimum": 1},
                        "after": _NULLABLE_STRING,
                    }
                ),
                _closed_object(
                    {
                        "op": {"type": "string", "enum": ["GET_LINEAGE_NODE"]},
                        "lineage_id": {"type": "string", "minLength": 1},
                    }
                ),
                _closed_object(
                    {
                        "op": {"type": "string", "enum": ["RELEASE_STATE"]},
                        "handle": {"type": "string", "minLength": 1},
                    }
                ),
            ],
        },
    ),
    ToolSpec(
        "submit_answer",
        "Submit the final answer and IDs of Evidence observed through index_read; use [] when empty.",
        _closed_object(
            {
                "answer": {"type": "string"},
                "evidence_ids": {
                    "type": "array",
                    "items": {"type": "string", "minLength": 1},
                    "uniqueItems": True,
                },
            }
        ),
    ),
)


def indexact_tool_specs() -> tuple[ToolSpec, ...]:
    """Return the immutable, provider-facing IndexAct tool contract."""

    return _TOOL_SPECS


def _error_json(error: OperationError) -> dict[str, JSONValue]:
    result: dict[str, JSONValue] = {
        "code": error.code.value if hasattr(error.code, "value") else str(error.code),
        "message": error.message,
    }
    if error.has_details:
        result["details"] = jsonable(error.details)
    return result


def _derivation_summary(
    operation: Operation,
    bindings: Mapping[str, str] | None = None,
) -> str:
    """Return a deterministic operation-only state derivation (never corpus text)."""

    resolved_bindings = dict(bindings or {})

    def resolve(value: object) -> object:
        if isinstance(value, Mapping):
            if set(value) == {"binding"} and value.get("binding") in resolved_bindings:
                return {"handle": resolved_bindings[str(value["binding"])]}
            return {key: resolve(item) for key, item in value.items()}
        if isinstance(value, list):
            return [resolve(item) for item in value]
        return value

    return json.dumps(
        resolve(operation.to_wire()),
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _tool_error(error: BaseException) -> ToolError:
    if isinstance(error, ProtocolError):
        value = error.error
        return ToolError(
            code=value.code.value if hasattr(value.code, "value") else str(value.code),
            message=value.message,
            details=jsonable(value.details) if value.has_details else None,
        )
    if isinstance(error, UnknownOutcomeError):
        return ToolError(
            code="UNKNOWN_OUTCOME",
            message=str(error),
            details={"operation": error.operation},
            outcome=Outcome.UNKNOWN,
        )
    if isinstance(error, (AgentInputError, TypeError, ValueError)):
        return ToolError("INVALID_TOOL_ARGUMENTS", str(error))
    if isinstance(error, ClientError):
        return ToolError(type(error).__name__.upper(), str(error))
    return ToolError(type(error).__name__.upper(), str(error))


def _short_lineage_id(lineage_id: str) -> str:
    return lineage_id[:16]


def _result_json(result: object) -> dict[str, JSONValue]:
    payload = jsonable(result)
    if not isinstance(payload, dict):
        return {"value": payload}
    if isinstance(result, ReadSuccess):
        payload["status"] = "success"
    elif isinstance(result, ReadBudgetExceeded):
        payload["status"] = "budget_exceeded"
        payload = {key: value for key, value in payload.items() if value is not None}
    return payload


def _safe_telemetry_result(result: object) -> dict[str, JSONValue]:
    if isinstance(result, StateCreated):
        return {"state": jsonable(result.state)}
    if isinstance(result, CountResult):
        return {"count": result.count}
    if isinstance(result, ReadSuccess):
        payload = _result_json(result)
        payload["evidence"] = [
            {
                "snapshot_id": item.snapshot_id,
                "doc_key": item.doc_key,
                "start": item.start,
                "end": item.end,
                "text_codepoints": len(item.text),
                "text_sha256": sha256(item.text.encode("utf-8")).hexdigest(),
            }
            for item in result.evidence
        ]
        return payload
    return _result_json(result)


@dataclass(slots=True)
class IndexActEnvironment:
    """Factory for task-isolated IndexAct environment sessions."""

    client: Any
    snapshot_id: str
    telemetry: _Telemetry | None = None
    protocol_version: str = "3.4"

    def open(self, task: Task) -> "IndexActEnvironmentSession":
        if not isinstance(task, Task):
            raise TypeError("task must be a Task")
        recorder = self.telemetry
        if recorder is None:
            recorder = OperationRecorder(task.task_id)
        return IndexActEnvironmentSession._open(
            self.client,
            self.snapshot_id,
            task,
            recorder,
            self.protocol_version,
        )


@dataclass(slots=True)
class IndexActEnvironmentSession:
    """One structured agent episode backed by one service session."""

    session: SessionTools
    task: Task
    telemetry: _Telemetry
    _states: dict[str, StateLedgerEntry] = field(default_factory=dict, init=False, repr=False)
    _state_order: list[str] = field(default_factory=list, init=False, repr=False)
    _evidence: list[EvidenceLedgerEntry] = field(default_factory=list, init=False, repr=False)
    _evidence_text: dict[str, str] = field(default_factory=dict, init=False, repr=False)
    _cursor_to_alias: dict[str, str] = field(default_factory=dict, init=False, repr=False)
    _alias_to_cursor: dict[str, str] = field(default_factory=dict, init=False, repr=False)
    _closed: bool = field(default=False, init=False, repr=False)
    _submitted: bool = field(default=False, init=False, repr=False)
    _tool_ordinal: int = field(default=0, init=False, repr=False)

    @classmethod
    def _open(
        cls,
        client: Any,
        snapshot_id: str,
        task: Task,
        telemetry: _Telemetry,
        protocol_version: str,
    ) -> "IndexActEnvironmentSession":
        request_id = telemetry.next_request_id()
        request = {"snapshot_id": snapshot_id, "protocol_version": protocol_version}
        try:
            session = SessionTools.open(client, snapshot_id, protocol_version)
        except BaseException as error:
            telemetry.record_operation(
                request_id=request_id,
                batch_index=None,
                operation="OPEN_SESSION",
                request=request,
                status="unknown" if isinstance(error, UnknownOutcomeError) else "error",
                error=_tool_error(error).to_json(),
            )
            raise
        telemetry.record_operation(
            request_id=request_id,
            batch_index=None,
            operation="OPEN_SESSION",
            request=request,
            status="completed",
            result={
                "protocol_version": session.protocol_version,
                "snapshot_id": session.corpus.snapshot_id,
                "corpus": jsonable(session.corpus),
                "service_limits": jsonable(session.service_limits),
            },
        )
        opened = cls(session, task, telemetry)
        opened._remember_state(session.corpus, None, "CORPUS snapshot root")
        return opened

    def __enter__(self) -> "IndexActEnvironmentSession":
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self.close()

    @property
    def session_id(self) -> str:
        return self.session.session_id

    @property
    def submitted(self) -> bool:
        return self._submitted

    def tool_specs(self) -> tuple[ToolSpec, ...]:
        return indexact_tool_specs()

    def state_ledger(self) -> StateLedgerView:
        return StateLedgerView(tuple(self._states[handle] for handle in self._state_order))

    def evidence_ledger(self) -> EvidenceLedgerView:
        return EvidenceLedgerView(tuple(self._evidence))

    @property
    def operation_events(self) -> tuple[object, ...]:
        """Return the immutable operation-event snapshot for the evaluation bridge."""

        return self.telemetry.events

    def execute(self, call: ToolCall) -> ToolResult:
        if not isinstance(call, ToolCall):
            raise TypeError("call must be a ToolCall")
        ordinal = self._tool_ordinal
        self._tool_ordinal += 1
        initial_event_count = self.telemetry.event_count
        if self._closed:
            return self._local_failure(call, "SESSION_CLOSED", "environment session is closed")
        if self._submitted:
            return self._local_failure(
                call, "EPISODE_TERMINATED", "an answer has already been submitted"
            )
        try:
            if call.name == "index_execute":
                return self._index_execute(call)
            if call.name == "index_read":
                return self._index_read(call, ordinal)
            if call.name == "index_state":
                return self._index_state(call)
            if call.name == "submit_answer":
                return self._submit_answer(call)
            return self._local_failure(call, "UNKNOWN_TOOL", f"unknown tool {call.name!r}")
        except (AgentInputError, ClientError, RuntimeError, TypeError, ValueError) as error:
            if self.telemetry.event_count == initial_event_count:
                return self._record_exception(call, error)
            return ToolResult.failure(call, _tool_error(error))

    def _index_execute(self, call: ToolCall) -> ToolResult:
        if set(call.arguments) != {"operations"}:
            raise AgentInputError("index_execute requires exactly one operations array")
        operations = parse_operations(call.arguments["operations"])
        disallowed = [
            operation.op for operation in operations if operation.op not in EXECUTE_OPERATIONS
        ]
        if disallowed or any(isinstance(operation, Read) for operation in operations):
            raise AgentInputError("READ is allowed only through index_read")
        request_id = self.telemetry.next_request_id()
        try:
            trace = self.session.execute(operations)
        except BaseException as error:
            self._record_batch_transport_failure(request_id, operations, error)
            raise

        bindings: dict[str, str] = {}
        for item in trace.completed:
            if not isinstance(item.result, (StateCreated, CountResult)):
                raise RuntimeError("index_execute received a text-capable or unknown result type")
            if isinstance(item.result, StateCreated):
                operation = operations[item.index]
                self._remember_state(
                    item.result.state,
                    call.call_id,
                    _derivation_summary(operation, bindings),
                )
                bind = getattr(operation, "bind", None)
                if isinstance(bind, str):
                    bindings[bind] = item.result.state.handle
        self._record_batch_trace(request_id, operations, trace)
        stable_trace = self._stable_payload(
            {
                "completed": [
                    {"index": item.index, "result": _result_json(item.result)}
                    for item in trace.completed
                ],
                **(
                    {
                        "failed_operation_index": trace.failed_operation_index,
                        "error": _error_json(trace.error),
                    }
                    if trace.error is not None
                    else {}
                ),
            }
        )
        if trace.error is not None:
            service_error = trace.error
            details: dict[str, JSONValue] = {
                "completed": stable_trace["completed"],
                "failed_operation_index": trace.failed_operation_index,
            }
            if service_error.has_details:
                details["service_details"] = jsonable(service_error.details)
            return ToolResult.failure(
                call,
                ToolError(
                    service_error.code.value
                    if hasattr(service_error.code, "value")
                    else str(service_error.code),
                    service_error.message,
                    details=details,
                ),
            )
        return ToolResult.success(call, stable_trace)

    def _index_read(self, call: ToolCall, ordinal: int) -> ToolResult:
        if "op" in call.arguments:
            raise AgentInputError("index_read arguments must not contain op")
        read_arguments = dict(call.arguments)
        if read_arguments.get("documents", object()) is None:
            read_arguments.pop("documents")
        operation = parse_operation({"op": "READ", **read_arguments})
        if not isinstance(operation, Read):  # pragma: no cover - parser invariant
            raise AssertionError("READ parser returned a non-READ operation")
        operation = self._transport_operation(operation)
        request_id = self.telemetry.next_request_id()
        try:
            trace = self.session.execute((operation,))
        except BaseException as error:
            self._record_batch_transport_failure(request_id, (operation,), error)
            raise
        self._record_batch_trace(request_id, (operation,), trace)
        if trace.error is not None:
            return ToolResult.failure(call, self._operation_tool_error(trace.error))
        if len(trace.completed) != 1 or trace.completed[0].index != 0:
            raise RuntimeError("service returned an invalid one-operation READ trace")
        result = trace.completed[0].result
        if not isinstance(result, (ReadSuccess, ReadBudgetExceeded)):
            raise RuntimeError("service returned a non-READ result for READ")
        payload = _result_json(result)
        if isinstance(result, ReadSuccess):
            enriched: list[dict[str, JSONValue]] = []
            originating_read = jsonable(dict(call.arguments))
            for item in result.evidence:
                evidence_id = f"e{len(self._evidence) + 1}"
                entry = EvidenceLedgerEntry(
                    evidence_id=evidence_id,
                    snapshot_id=item.snapshot_id,
                    doc_key=item.doc_key,
                    start=item.start,
                    end=item.end,
                    text_sha256=sha256(item.text.encode("utf-8")).hexdigest(),
                    full_text_retained=True,
                    originating_call_id=call.call_id,
                    originating_turn=ordinal,
                    originating_read=originating_read,
                )
                self._evidence.append(entry)
                self._evidence_text[evidence_id] = item.text
                evidence_value = jsonable(item)
                evidence_value["evidence_id"] = evidence_id
                enriched.append(evidence_value)
            payload["evidence"] = enriched
        stable = self._stable_payload(payload)
        contains_text = isinstance(result, ReadSuccess) and bool(result.evidence)
        return ToolResult.success(call, stable, contains_corpus_text=contains_text)

    def _index_state(self, call: ToolCall) -> ToolResult:
        arguments = dict(call.arguments)
        op = arguments.get("op")
        if op not in STATE_OPERATIONS:
            raise AgentInputError(f"unknown index_state operation {op!r}")
        request_id = self.telemetry.next_request_id()
        request = dict(call.arguments)
        try:
            if op == "LIST_STATES":
                if set(arguments) != {"op", "limit", "after"}:
                    raise AgentInputError("LIST_STATES requires op, limit, and after only")
                after = arguments.get("after")
                real_after = self._alias_to_cursor.get(after, after)
                result = self.session.list_states(arguments["limit"], real_after)
                for state in result.states:
                    self._remember_state(state, None)
            elif op == "GET_LINEAGE_NODE":
                if set(arguments) != {"op", "lineage_id"}:
                    raise AgentInputError("GET_LINEAGE_NODE requires op and lineage_id only")
                result = self.session.get_lineage_node(arguments["lineage_id"])
            else:
                if set(arguments) != {"op", "handle"}:
                    raise AgentInputError("RELEASE_STATE requires op and handle only")
                handle = arguments["handle"]
                result = self.session.release_state(handle)
                if result.released and handle in self._states:
                    self._states[handle] = replace(self._states[handle], active=False)
        except BaseException as error:
            self.telemetry.record_operation(
                request_id=request_id,
                batch_index=None,
                operation=op,
                request=request,
                status="unknown" if isinstance(error, UnknownOutcomeError) else "error",
                error=_tool_error(error).to_json(),
            )
            raise
        payload = self._stable_payload(jsonable(result))
        self.telemetry.record_operation(
            request_id=request_id,
            batch_index=None,
            operation=op,
            request=request,
            status="completed",
            result=payload,
        )
        return ToolResult.success(call, payload)

    def _submit_answer(self, call: ToolCall) -> ToolResult:
        if set(call.arguments) != {"answer", "evidence_ids"}:
            raise AgentInputError("submit_answer requires answer and evidence_ids")
        answer = call.arguments["answer"]
        if not isinstance(answer, str):
            raise AgentInputError("submit_answer answer must be a string")
        raw_ids = call.arguments["evidence_ids"]
        if isinstance(raw_ids, (str, bytes)) or not isinstance(raw_ids, Sequence):
            raise AgentInputError("submit_answer evidence_ids must be an array")
        if not all(isinstance(value, str) and value for value in raw_ids):
            raise AgentInputError("submit_answer evidence_ids must contain non-empty strings")
        evidence_ids = list(raw_ids)
        known = {entry.evidence_id for entry in self._evidence}
        missing = [value for value in evidence_ids if value not in known]
        if missing:
            return self._local_failure(
                call,
                "INVALID_EVIDENCE_ID",
                "submitted evidence_ids must refer to Evidence observed in this episode",
                details={"unknown_evidence_ids": missing},
            )
        value: dict[str, JSONValue] = {
            "answer": answer,
            "evidence_ids": evidence_ids,
            "submitted": True,
        }
        self._submitted = True
        request_id = self.telemetry.next_request_id()
        self.telemetry.record_operation(
            request_id=request_id,
            batch_index=None,
            operation="SUBMIT_ANSWER",
            request={"answer": answer, "evidence_ids": evidence_ids},
            status="completed",
            result=value,
        )
        return ToolResult.success(call, value)

    def _remember_state(
        self,
        state: StateMetadata,
        call_id: str | None,
        derivation_summary: str | None = None,
    ) -> None:
        existing = self._states.get(state.handle)
        if derivation_summary is None:
            derivation_summary = (
                existing.derivation_summary
                if existing is not None
                else f"{state.created_by.value} state {state.handle}"
            )
        entry = StateLedgerEntry(
            handle=state.handle,
            state_type=state.state_type.value,
            cardinality=state.cardinality,
            creator=state.created_by.value,
            short_lineage_id=_short_lineage_id(state.lineage_id),
            active=True,
            derivation_summary=derivation_summary,
            note=existing.note if existing is not None else None,
            originating_call_id=(
                call_id
                if call_id is not None
                else existing.originating_call_id
                if existing is not None
                else None
            ),
        )
        if existing is None:
            self._state_order.append(state.handle)
        self._states[state.handle] = entry

    def _cursor_alias(self, cursor: str) -> str:
        cached = self._cursor_to_alias.get(cursor)
        if cached is not None:
            return cached
        if cursor.startswith("cur-"):
            prefix = "cur-"
        elif cursor.startswith("stc-"):
            prefix = "stc-"
        else:
            return cursor
        ordinal = 1 + sum(alias.startswith(prefix) for alias in self._alias_to_cursor)
        alias = prefix + f"{ordinal:064x}"
        while alias in self._alias_to_cursor:
            ordinal += 1
            alias = prefix + f"{ordinal:064x}"
        self._cursor_to_alias[cursor] = alias
        self._alias_to_cursor[alias] = cursor
        return alias

    def _stable_payload(self, value: Any) -> Any:
        work: list[tuple[Any, bool, bool]] = [(value, False, False)]
        built: list[Any] = []
        while work:
            current, expanded, cursor_field = work.pop()
            if not expanded and isinstance(current, (Mapping, list, tuple)):
                work.append((current, True, cursor_field))
                if isinstance(current, Mapping):
                    work.extend(
                        (item, False, key in {"after", "next_cursor"})
                        for key, item in reversed(list(current.items()))
                    )
                else:
                    work.extend((item, False, False) for item in reversed(current))
                continue
            if isinstance(current, Mapping):
                keys = list(current)
                children = built[-len(keys) :] if keys else []
                if keys:
                    del built[-len(keys) :]
                built.append(dict(zip(keys, children)))
            elif isinstance(current, (list, tuple)):
                children = built[-len(current) :] if current else []
                if current:
                    del built[-len(current) :]
                built.append(list(children))
            elif cursor_field and isinstance(current, str):
                built.append(self._cursor_alias(current))
            else:
                built.append(current)
        return built[0]

    def _transport_operation(self, operation: Read) -> Read:
        if isinstance(operation.documents, Page) and operation.documents.after is not None:
            real = self._alias_to_cursor.get(operation.documents.after, operation.documents.after)
            if real != operation.documents.after:
                return replace(operation, documents=replace(operation.documents, after=real))
        return operation

    def _operation_tool_error(self, error: OperationError) -> ToolError:
        return ToolError(
            error.code.value if hasattr(error.code, "value") else str(error.code),
            error.message,
            details=jsonable(error.details) if error.has_details else None,
        )

    def _record_batch_trace(
        self, request_id: str, operations: Sequence[Operation], trace: ExecuteTrace
    ) -> None:
        completed = {item.index: item.result for item in trace.completed}
        if not operations:
            self.telemetry.record_operation(
                request_id=request_id,
                batch_index=None,
                operation="EXECUTE",
                request={"ops": []},
                status="completed",
                result={"completed": []},
            )
            return
        for index, operation in enumerate(operations):
            if index in completed:
                self.telemetry.record_operation(
                    request_id=request_id,
                    batch_index=index,
                    operation=operation.op,
                    request=operation.to_wire(),
                    status="completed",
                    result=self._stable_payload(_safe_telemetry_result(completed[index])),
                )
            elif trace.failed_operation_index == index and trace.error is not None:
                self.telemetry.record_operation(
                    request_id=request_id,
                    batch_index=index,
                    operation=operation.op,
                    request=operation.to_wire(),
                    status="failed",
                    error=_error_json(trace.error),
                )
            else:
                self.telemetry.record_operation(
                    request_id=request_id,
                    batch_index=index,
                    operation=operation.op,
                    request=operation.to_wire(),
                    status="not_executed",
                )

    def _record_batch_transport_failure(
        self, request_id: str, operations: Sequence[Operation], error: BaseException
    ) -> None:
        detail = _tool_error(error).to_json()
        for index, operation in enumerate(operations):
            self.telemetry.record_operation(
                request_id=request_id,
                batch_index=index,
                operation=operation.op,
                request=operation.to_wire(),
                status="unknown" if isinstance(error, UnknownOutcomeError) else "error",
                error=detail,
            )

    def _record_exception(self, call: ToolCall, error: BaseException) -> ToolResult:
        tool_error = _tool_error(error)
        if tool_error.outcome is Outcome.UNKNOWN:
            status = "unknown"
        elif isinstance(error, (AgentInputError, TypeError, ValueError)):
            status = "validation_error"
        else:
            status = "error"
        request_id = self.telemetry.next_request_id()
        self.telemetry.record_operation(
            request_id=request_id,
            batch_index=None,
            operation=call.name.upper() or "UNKNOWN_TOOL",
            request=dict(call.arguments),
            status=status,
            error=tool_error.to_json(),
        )
        return ToolResult.failure(call, tool_error)

    def _local_failure(
        self,
        call: ToolCall,
        code: str,
        message: str,
        *,
        details: JSONValue | None = None,
    ) -> ToolResult:
        error = ToolError(code, message, details=details)
        request_id = self.telemetry.next_request_id()
        self.telemetry.record_operation(
            request_id=request_id,
            batch_index=None,
            operation=call.name.upper() or "UNKNOWN_TOOL",
            request=dict(call.arguments),
            status="validation_error",
            error=error.to_json(),
        )
        return ToolResult.failure(call, error)

    def close(self) -> None:
        if self._closed:
            return
        request_id = self.telemetry.next_request_id()
        try:
            self.session.close()
        except BaseException as error:
            self.telemetry.record_operation(
                request_id=request_id,
                batch_index=None,
                operation="CLOSE_SESSION",
                request={},
                status="error",
                error=_tool_error(error).to_json(),
            )
            raise
        self._closed = True
        self.telemetry.record_operation(
            request_id=request_id,
            batch_index=None,
            operation="CLOSE_SESSION",
            request={},
            status="completed",
            result={"closed": True},
        )


__all__ = [
    "EXECUTE_OPERATIONS",
    "IndexActEnvironment",
    "IndexActEnvironmentSession",
    "OperationRecord",
    "OperationRecorder",
    "STATE_OPERATIONS",
]
