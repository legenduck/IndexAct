"""Provider-neutral canonical types for the IndexAct interface.

The runtime deliberately keeps these values independent of any model SDK and
of the IndexAct environment implementation.  Provider response IDs and
metadata are annotations; local message sequence numbers are the canonical
conversation identity.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from enum import Enum
from typing import Mapping, Protocol, TypeAlias, runtime_checkable


JSONScalar: TypeAlias = None | bool | int | float | str
JSONValue: TypeAlias = JSONScalar | list["JSONValue"] | dict[str, "JSONValue"]


def _require_text(value: object, name: str, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or (not allow_empty and not value):
        qualifier = "a string" if allow_empty else "a non-empty string"
        raise ValueError(f"{name} must be {qualifier}")
    return value


def _require_sequence(value: object) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("message sequence must be a non-negative integer")
    return value


def _validate_json(value: object, name: str) -> None:
    active: set[int] = set()
    pending: list[tuple[object, bool]] = [(value, False)]
    while pending:
        current, expanded = pending.pop()
        if current is None or type(current) is bool or isinstance(current, (int, str)):
            continue
        if isinstance(current, float):
            if not math.isfinite(current):
                raise ValueError(f"{name} contains a non-finite number")
            continue
        if isinstance(current, (list, dict)):
            identity = id(current)
            if expanded:
                active.remove(identity)
                continue
            if identity in active:
                raise ValueError(f"{name} contains a cycle")
            active.add(identity)
            pending.append((current, True))
            if isinstance(current, dict):
                if not all(isinstance(key, str) for key in current):
                    raise TypeError(f"{name} contains a non-string object key")
                children = current.values()
            else:
                children = current
            for child in children:
                pending.append((child, False))
            continue
        raise TypeError(f"{name} contains a non-JSON value: {type(current).__name__}")


@dataclass(frozen=True, slots=True)
class Task:
    task_id: str
    text: str
    metadata: Mapping[str, JSONValue] = field(default_factory=dict)

    def __post_init__(self) -> None:
        _require_text(self.task_id, "task_id")
        _require_text(self.text, "task text", allow_empty=True)
        if not isinstance(self.metadata, Mapping):
            raise TypeError("task metadata must be a mapping")
        metadata = dict(self.metadata)
        _validate_json(metadata, "task metadata")
        object.__setattr__(self, "metadata", metadata)


@dataclass(frozen=True, slots=True)
class ToolSpec:
    name: str
    description: str
    parameters: Mapping[str, JSONValue]
    strict: bool = True

    def __post_init__(self) -> None:
        _require_text(self.name, "tool name")
        _require_text(self.description, "tool description", allow_empty=True)
        if not isinstance(self.parameters, Mapping):
            raise TypeError("tool parameters must be a JSON Schema mapping")
        if type(self.strict) is not bool:
            raise TypeError("tool strict must be boolean")
        schema = dict(self.parameters)
        _validate_json(schema, "tool parameters")
        if schema.get("type") != "object":
            raise ValueError("tool parameters must be an object JSON Schema")
        properties = schema.get("properties")
        variants = schema.get("oneOf")
        if not isinstance(properties, Mapping) and not (
            isinstance(variants, list)
            and variants
            and all(isinstance(variant, Mapping) for variant in variants)
        ):
            raise ValueError(
                "tool parameters require object properties or object oneOf variants"
            )
        if self.strict:
            strict_objects = [schema] if isinstance(properties, Mapping) else list(variants)
            for strict_object in strict_objects:
                variant_properties = strict_object.get("properties")
                required = strict_object.get("required")
                if (
                    strict_object.get("type") != "object"
                    or not isinstance(variant_properties, Mapping)
                    or strict_object.get("additionalProperties") is not False
                ):
                    raise ValueError(
                        "strict tool schema variants require closed object schemas"
                    )
                if (
                    not isinstance(required, list)
                    or not all(isinstance(item, str) for item in required)
                    or set(required) != set(variant_properties)
                ):
                    raise ValueError("strict tool schema requires every property")
        object.__setattr__(self, "parameters", schema)


@dataclass(frozen=True, slots=True)
class ToolCall:
    call_id: str
    name: str
    arguments: Mapping[str, JSONValue]

    def __post_init__(self) -> None:
        _require_text(self.call_id, "tool call_id")
        _require_text(self.name, "tool name")
        if not isinstance(self.arguments, Mapping):
            raise TypeError("tool arguments must be a JSON object")
        arguments = dict(self.arguments)
        _validate_json(arguments, "tool arguments")
        object.__setattr__(self, "arguments", arguments)


class ToolStatus(str, Enum):
    SUCCESS = "success"
    ERROR = "error"


class Outcome(str, Enum):
    KNOWN = "KNOWN"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True, slots=True)
class ToolError:
    code: str
    message: str
    details: JSONValue | None = None
    retryable: bool = False
    outcome: Outcome = Outcome.KNOWN

    def __post_init__(self) -> None:
        _require_text(self.code, "tool error code")
        _require_text(self.message, "tool error message", allow_empty=True)
        if type(self.retryable) is not bool:
            raise TypeError("tool error retryable must be boolean")
        if not isinstance(self.outcome, Outcome):
            object.__setattr__(self, "outcome", Outcome(self.outcome))
        _validate_json(self.details, "tool error details")

    def to_json(self) -> dict[str, JSONValue]:
        value: dict[str, JSONValue] = {
            "code": self.code,
            "message": self.message,
            "retryable": self.retryable,
            "outcome": self.outcome.value,
        }
        if self.details is not None:
            value["details"] = self.details
        return value


@dataclass(frozen=True, slots=True)
class ToolResult:
    call_id: str
    name: str
    status: ToolStatus
    value: JSONValue | None = None
    error: ToolError | None = None
    contains_corpus_text: bool = False

    def __post_init__(self) -> None:
        _require_text(self.call_id, "tool result call_id")
        _require_text(self.name, "tool result name")
        if not isinstance(self.status, ToolStatus):
            object.__setattr__(self, "status", ToolStatus(self.status))
        if type(self.contains_corpus_text) is not bool:
            raise TypeError("contains_corpus_text must be boolean")
        if self.status is ToolStatus.SUCCESS and self.error is not None:
            raise ValueError("successful ToolResult must not contain error")
        if self.status is ToolStatus.ERROR and self.error is None:
            raise ValueError("error ToolResult requires structured error")
        if self.status is ToolStatus.ERROR and self.value is not None:
            raise ValueError("error ToolResult must not contain value")
        if self.status is ToolStatus.ERROR and self.contains_corpus_text:
            raise ValueError("error ToolResult cannot contain corpus text")
        _validate_json(self.value, "tool result value")

    @classmethod
    def success(
        cls,
        call: ToolCall,
        value: JSONValue | None,
        *,
        contains_corpus_text: bool = False,
    ) -> "ToolResult":
        return cls(
            call_id=call.call_id,
            name=call.name,
            status=ToolStatus.SUCCESS,
            value=value,
            contains_corpus_text=contains_corpus_text,
        )

    @classmethod
    def failure(cls, call: ToolCall, error: ToolError) -> "ToolResult":
        return cls(
            call_id=call.call_id,
            name=call.name,
            status=ToolStatus.ERROR,
            error=error,
        )

    def to_json(self) -> dict[str, JSONValue]:
        result: dict[str, JSONValue] = {
            "call_id": self.call_id,
            "name": self.name,
            "status": self.status.value,
            "contains_corpus_text": self.contains_corpus_text,
        }
        if self.status is ToolStatus.SUCCESS:
            result["value"] = self.value
        else:
            assert self.error is not None
            result["error"] = self.error.to_json()
        return result


@dataclass(frozen=True, slots=True)
class SystemMessage:
    sequence: int
    text: str

    def __post_init__(self) -> None:
        _require_sequence(self.sequence)
        _require_text(self.text, "system message", allow_empty=True)


@dataclass(frozen=True, slots=True)
class DeveloperMessage:
    """Runtime-authored context separate from fixed instructions and the user task."""

    sequence: int
    text: str

    def __post_init__(self) -> None:
        _require_sequence(self.sequence)
        _require_text(self.text, "developer message", allow_empty=True)


@dataclass(frozen=True, slots=True)
class UserMessage:
    sequence: int
    text: str

    def __post_init__(self) -> None:
        _require_sequence(self.sequence)
        _require_text(self.text, "user message", allow_empty=True)


@dataclass(frozen=True, slots=True)
class AssistantText:
    sequence: int
    text: str
    provider_response_id: str | None = None
    provider_metadata: Mapping[str, JSONValue] = field(default_factory=dict)

    def __post_init__(self) -> None:
        _require_sequence(self.sequence)
        _require_text(self.text, "assistant text", allow_empty=True)
        metadata = dict(self.provider_metadata)
        _validate_json(metadata, "assistant provider metadata")
        object.__setattr__(self, "provider_metadata", metadata)


@dataclass(frozen=True, slots=True)
class AssistantToolCall:
    sequence: int
    call: ToolCall
    provider_response_id: str | None = None
    provider_metadata: Mapping[str, JSONValue] = field(default_factory=dict)

    def __post_init__(self) -> None:
        _require_sequence(self.sequence)
        if not isinstance(self.call, ToolCall):
            raise TypeError("AssistantToolCall.call must be ToolCall")
        metadata = dict(self.provider_metadata)
        _validate_json(metadata, "assistant provider metadata")
        object.__setattr__(self, "provider_metadata", metadata)


@dataclass(frozen=True, slots=True)
class ToolResultMessage:
    sequence: int
    result: ToolResult

    def __post_init__(self) -> None:
        _require_sequence(self.sequence)
        if not isinstance(self.result, ToolResult):
            raise TypeError("ToolResultMessage.result must be ToolResult")


RuntimeMessage: TypeAlias = (
    SystemMessage
    | DeveloperMessage
    | UserMessage
    | AssistantText
    | AssistantToolCall
    | ToolResultMessage
)


@dataclass(frozen=True, slots=True)
class ModelUsage:
    input_tokens: int | None = None
    cached_input_tokens: int | None = None
    output_tokens: int | None = None
    reasoning_tokens: int | None = None
    total_tokens: int | None = None
    cache_write_tokens: int | None = None

    def __post_init__(self) -> None:
        for name in self.__dataclass_fields__:
            value = getattr(self, name)
            if value is not None and (
                isinstance(value, bool) or not isinstance(value, int) or value < 0
            ):
                raise ValueError(f"{name} must be a non-negative integer or None")


@dataclass(frozen=True, slots=True)
class ModelRequest:
    messages: tuple[RuntimeMessage, ...]
    tools: tuple[ToolSpec, ...]
    tool_choice: str | Mapping[str, JSONValue] = "auto"
    parallel_tool_calls: bool = False
    max_output_tokens: int | None = None
    reasoning: Mapping[str, JSONValue] = field(default_factory=dict)
    metadata: Mapping[str, JSONValue] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "messages", tuple(self.messages))
        object.__setattr__(self, "tools", tuple(self.tools))
        if type(self.parallel_tool_calls) is not bool:
            raise TypeError("parallel_tool_calls must be boolean")
        if self.max_output_tokens is not None and (
            isinstance(self.max_output_tokens, bool)
            or not isinstance(self.max_output_tokens, int)
            or self.max_output_tokens < 1
        ):
            raise ValueError("max_output_tokens must be a positive integer or None")
        reasoning = dict(self.reasoning)
        metadata = dict(self.metadata)
        _validate_json(reasoning, "model reasoning")
        _validate_json(metadata, "model metadata")
        object.__setattr__(self, "reasoning", reasoning)
        object.__setattr__(self, "metadata", metadata)


@dataclass(frozen=True, slots=True)
class ModelTurn:
    visible_text: str
    tool_call: ToolCall | None
    stop_reason: str | None
    usage: ModelUsage = field(default_factory=ModelUsage)
    provider_response_id: str | None = None
    provider_metadata: Mapping[str, JSONValue] = field(default_factory=dict)
    tool_calls: tuple[ToolCall, ...] = ()

    def __post_init__(self) -> None:
        _require_text(self.visible_text, "visible_text", allow_empty=True)
        if self.tool_call is not None and not isinstance(self.tool_call, ToolCall):
            raise TypeError("tool_call must be ToolCall or None")
        calls = tuple(self.tool_calls)
        if not all(isinstance(call, ToolCall) for call in calls):
            raise TypeError("tool_calls must contain only ToolCall values")
        if self.tool_call is not None:
            if calls and calls[0] != self.tool_call:
                raise ValueError("tool_call must equal the first tool_calls item")
            if not calls:
                calls = (self.tool_call,)
        elif calls:
            object.__setattr__(self, "tool_call", calls[0])
        if len({call.call_id for call in calls}) != len(calls):
            raise ValueError("tool call IDs must be unique within a model turn")
        object.__setattr__(self, "tool_calls", calls)
        metadata = dict(self.provider_metadata)
        _validate_json(metadata, "model provider metadata")
        object.__setattr__(self, "provider_metadata", metadata)


@dataclass(frozen=True, slots=True)
class StateLedgerEntry:
    handle: str
    state_type: str
    cardinality: int
    creator: str
    short_lineage_id: str
    active: bool
    derivation_summary: str
    note: str | None = None
    originating_call_id: str | None = None


@dataclass(frozen=True, slots=True)
class StateLedgerView:
    entries: tuple[StateLedgerEntry, ...] = ()


@dataclass(frozen=True, slots=True)
class EvidenceLedgerEntry:
    evidence_id: str
    snapshot_id: str
    doc_key: str
    start: int
    end: int
    text_sha256: str
    full_text_retained: bool
    originating_call_id: str
    originating_turn: int
    originating_read: Mapping[str, JSONValue] = field(default_factory=dict)

    def __post_init__(self) -> None:
        originating_read = dict(self.originating_read)
        _validate_json(originating_read, "originating READ")
        object.__setattr__(self, "originating_read", originating_read)


@dataclass(frozen=True, slots=True)
class EvidenceLedgerView:
    entries: tuple[EvidenceLedgerEntry, ...] = ()


@runtime_checkable
class EnvironmentSession(Protocol):
    def tool_specs(self) -> tuple[ToolSpec, ...]: ...

    def execute(self, call: ToolCall) -> ToolResult: ...

    def state_ledger(self) -> StateLedgerView: ...

    def evidence_ledger(self) -> EvidenceLedgerView: ...

    def close(self) -> None: ...


@runtime_checkable
class AgentEnvironment(Protocol):
    def open(self, task: Task) -> EnvironmentSession: ...


@dataclass(frozen=True, slots=True)
class RetryRecord:
    attempt: int
    delay_seconds: float
    code: str
    message: str


@dataclass(frozen=True, slots=True)
class RuntimeEvent:
    kind: str
    model_call: int
    turn: int
    details: Mapping[str, JSONValue] = field(default_factory=dict)

    def __post_init__(self) -> None:
        details = dict(self.details)
        _validate_json(details, "runtime event details")
        object.__setattr__(self, "details", details)


__all__ = [
    "AgentEnvironment",
    "AssistantText",
    "AssistantToolCall",
    "DeveloperMessage",
    "EnvironmentSession",
    "EvidenceLedgerEntry",
    "EvidenceLedgerView",
    "JSONScalar",
    "JSONValue",
    "ModelRequest",
    "ModelTurn",
    "ModelUsage",
    "Outcome",
    "RetryRecord",
    "RuntimeEvent",
    "RuntimeMessage",
    "StateLedgerEntry",
    "StateLedgerView",
    "SystemMessage",
    "Task",
    "ToolCall",
    "ToolError",
    "ToolResult",
    "ToolResultMessage",
    "ToolSpec",
    "ToolStatus",
    "UserMessage",
]
