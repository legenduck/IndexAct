"""OpenAI strict function-schema dialect for the canonical IndexAct tool specs.

The canonical tool contract is ordinary JSON Schema: closed objects, ``oneOf``
variants, and ``uniqueItems``.  OpenAI strict function calling rejects exactly
three things in that contract (verified against the live API):

1. ``oneOf`` anywhere — only ``anyOf`` is accepted;
2. ``uniqueItems`` — not a supported keyword;
3. a root schema that is itself a choice — the root must be a plain object
   with ``properties``.

This module rewrites a canonical spec into that dialect for the wire only.
Every variant in the canonical contract is mutually exclusive (distinct closed
key sets or distinct JSON types), so ``anyOf`` denotes the same value set as
``oneOf``.  A choice-rooted tool is wrapped under one ``request`` property and
its arguments are unwrapped again on decode, so canonical tool calls, local
validation, ledgers, artifacts, and the tool-schema hash are unaffected.
"""

from __future__ import annotations

from typing import Mapping

from .types import JSONValue


WRAPPER_PROPERTY = "request"


def encode_parameters(parameters: Mapping[str, JSONValue]) -> tuple[dict[str, JSONValue], bool]:
    """Return ``(strict_schema, wrapped)`` for one canonical tool parameter schema."""

    schema = _rewrite(dict(parameters))
    if not isinstance(schema, dict):  # pragma: no cover - root is always an object
        raise TypeError("tool parameters must be an object schema")
    if isinstance(schema.get("properties"), Mapping):
        return schema, False
    definitions = schema.pop("$defs", None)
    # The root becomes a plain object; the choice moves under one property.
    inner = {key: value for key, value in schema.items() if key != "type"}
    outer: dict[str, JSONValue] = {
        "type": "object",
        "properties": {WRAPPER_PROPERTY: inner},
        "required": [WRAPPER_PROPERTY],
        "additionalProperties": False,
    }
    if definitions is not None:
        outer["$defs"] = definitions
    return outer, True


def encode_arguments(
    name: str,
    arguments: Mapping[str, JSONValue],
    wrapped_tools: frozenset[str] | set[str],
) -> dict[str, JSONValue]:
    """Canonical arguments -> wire arguments for history replay."""

    if name in wrapped_tools:
        return {WRAPPER_PROPERTY: dict(arguments)}
    return dict(arguments)


def decode_arguments(
    name: str,
    arguments: Mapping[str, JSONValue],
    wrapped_tools: frozenset[str] | set[str],
) -> dict[str, JSONValue]:
    """Wire arguments -> canonical arguments.

    A wrapped call that does not have the exact ``{"request": {...}}`` shape is
    returned unchanged so the runtime's canonical validation reports it to the
    model as ``INVALID_TOOL_ARGUMENTS`` instead of guessing.
    """

    if (
        name in wrapped_tools
        and set(arguments) == {WRAPPER_PROPERTY}
        and isinstance(arguments[WRAPPER_PROPERTY], Mapping)
    ):
        return dict(arguments[WRAPPER_PROPERTY])
    return dict(arguments)


def _rewrite(node: JSONValue) -> JSONValue:
    if isinstance(node, Mapping):
        result: dict[str, JSONValue] = {}
        for key, value in node.items():
            if key == "uniqueItems":
                continue
            result["anyOf" if key == "oneOf" else key] = _rewrite(value)
        return result
    if isinstance(node, list):
        return [_rewrite(item) for item in node]
    return node


__all__ = ["WRAPPER_PROPERTY", "decode_arguments", "encode_arguments", "encode_parameters"]
