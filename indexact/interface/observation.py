"""Model-facing projections of canonical runtime observations.

Canonical tool results retain protocol and audit metadata for validation,
telemetry, and durable artifacts.  Model requests receive only the fields that
can affect an agent decision during the normal IndexAct tool workflow.
"""

from __future__ import annotations

from .types import JSONValue, ToolResult, ToolStatus


_EXECUTE_READ_TOOLS = frozenset({"index_execute", "index_read"})
_INTERNAL_VALUE_FIELDS = frozenset({"lineage_id", "snapshot_id"})


def model_tool_result_json(result: ToolResult) -> dict[str, JSONValue]:
    """Return the provider-visible form of one canonical tool result.

    The provider function-call envelope already associates an output with its
    call and tool. ``contains_corpus_text`` is a local enforcement flag.  None
    of those values belong in the JSON observation seen by the model.

    Routine execute/read results additionally omit snapshot and lineage IDs.
    The typed client and canonical history keep them.  ``index_state`` keeps
    these IDs for explicit state and lineage inspection.
    """

    projected: dict[str, JSONValue] = {"status": result.status.value}
    if result.status is ToolStatus.ERROR:
        assert result.error is not None
        error: JSONValue = result.error.to_json()
        if result.name in _EXECUTE_READ_TOOLS:
            error = _without_internal_value_fields(error)
        projected["error"] = error
        return projected

    value = result.value
    if result.name in _EXECUTE_READ_TOOLS:
        value = _without_internal_value_fields(value)
    projected["value"] = value
    return projected


def _without_internal_value_fields(value: JSONValue) -> JSONValue:
    if isinstance(value, list):
        return [_without_internal_value_fields(item) for item in value]
    if isinstance(value, dict):
        return {
            key: _without_internal_value_fields(item)
            for key, item in value.items()
            if key not in _INTERNAL_VALUE_FIELDS
        }
    return value
