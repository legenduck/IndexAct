"""Strict decoding of untrusted agent JSON into the approved client DSL."""

from __future__ import annotations

from dataclasses import fields, is_dataclass
from enum import Enum
from typing import Any, Mapping, Sequence

from indexact.interface.client.dsl import (
    AllDocuments,
    AllOccurrences,
    And,
    AnyOf,
    Around,
    AsSet,
    BindingRef,
    Combine,
    Count,
    CountDocs,
    Difference,
    DocKeyTarget,
    Document,
    Filter,
    First,
    HandleRef,
    Intersect,
    LexicalExpression,
    Near,
    Not,
    Nth,
    Operation,
    Or,
    Page,
    Phrase,
    Range,
    Rank,
    Read,
    ReadBudget,
    RegionSpec,
    Restrict,
    ScoringExpression,
    StateRef,
    StateTarget,
    Term,
    TextCondition,
    TopK,
    Union,
    Weight,
    WeightedTerm,
)


class AgentInputError(ValueError):
    """An agent tool call does not have an approved operation shape."""


def _object(value: object, field: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise AgentInputError(f"{field} must be an object")
    if not all(isinstance(key, str) for key in value):
        raise AgentInputError(f"{field} property names must be strings")
    return value


def _array(value: object, field: str) -> Sequence[Any]:
    if isinstance(value, (str, bytes)) or not isinstance(value, Sequence):
        raise AgentInputError(f"{field} must be an array")
    return value


def _keys(
    value: Mapping[str, Any],
    required: set[str],
    optional: set[str] | None = None,
    *,
    field: str,
) -> None:
    optional = optional or set()
    missing = required - set(value)
    extra = set(value) - required - optional
    if missing:
        raise AgentInputError(f"{field} is missing {', '.join(sorted(missing))}")
    if extra:
        raise AgentInputError(f"{field} has unknown field(s): {', '.join(sorted(extra))}")


def _tag(value: object, field: str) -> tuple[str, Any]:
    obj = _object(value, field)
    if len(obj) != 1:
        raise AgentInputError(f"{field} must contain exactly one variant tag")
    return next(iter(obj.items()))


def parse_lexical(value: object) -> LexicalExpression:
    parsed = _parse_text_expression(value, lexical_only=True)
    assert isinstance(parsed, LexicalExpression)
    return parsed


def parse_condition(value: object) -> TextCondition:
    return _parse_text_expression(value, lexical_only=False)


def _parse_text_expression(
    value: object, *, lexical_only: bool
) -> TextCondition | LexicalExpression:
    work: list[tuple[object, bool, bool, tuple[str, Any, int] | None]] = [
        (value, lexical_only, False, None)
    ]
    built: list[TextCondition | LexicalExpression] = []
    while work:
        raw, lexical_mode, expanded, metadata = work.pop()
        if expanded:
            assert metadata is not None
            tag, node_data, child_count = metadata
            children = tuple(built[-child_count:]) if child_count else ()
            if child_count:
                del built[-child_count:]
            if tag == "term":
                built.append(Term(node_data))
            elif tag == "any_of":
                built.append(AnyOf(children))  # type: ignore[arg-type]
            elif tag == "phrase":
                built.append(Phrase(children))  # type: ignore[arg-type]
            elif tag == "near":
                ordered, max_gaps = node_data
                built.append(
                    Near(children, ordered=ordered, max_gaps=max_gaps)  # type: ignore[arg-type]
                )
            elif tag == "and":
                built.append(And(children))
            elif tag == "or":
                built.append(Or(children))
            else:
                built.append(Not(children[0]))
            continue

        tag, payload = _tag(raw, "LexicalExpression" if lexical_mode else "TextCondition")
        if not lexical_mode and tag in {"and", "or", "not"}:
            children = [payload] if tag == "not" else list(_array(payload, tag))
            work.append((raw, lexical_mode, True, (tag, None, len(children))))
            work.extend((child, False, False, None) for child in reversed(children))
            continue
        if tag == "term":
            work.append((raw, True, True, (tag, payload, 0)))
            continue
        if tag in {"any_of", "phrase"}:
            children = list(_array(payload, tag))
            node_data: Any = None
        elif tag == "near":
            obj = _object(payload, "near")
            _keys(obj, {"children", "ordered", "max_gaps"}, field="near")
            children = list(_array(obj["children"], "near.children"))
            node_data = (obj["ordered"], obj["max_gaps"])
        else:
            kind = "lexical-expression" if lexical_mode else "text-condition"
            raise AgentInputError(f"unknown {kind} tag {tag!r}")
        work.append((raw, True, True, (tag, node_data, len(children))))
        work.extend((child, True, False, None) for child in reversed(children))

    if len(built) != 1:  # pragma: no cover - work-stack invariant
        raise AssertionError("expression parser did not produce one root")
    return built[0]


def parse_scoring(value: object) -> ScoringExpression:
    tag, payload = _tag(value, "ScoringExpression")
    if tag == "term":
        return Term(payload)
    if tag == "combine":
        terms = tuple(parse_lexical(item) for item in _array(payload, "combine"))
        if not all(isinstance(term, Term) for term in terms):
            raise AgentInputError("COMBINE children must all be TERM")
        return Combine(terms)
    if tag == "weight":
        atoms: list[WeightedTerm] = []
        for index, item in enumerate(_array(payload, "weight")):
            obj = _object(item, f"weight[{index}]")
            _keys(obj, {"weight", "term"}, field=f"weight[{index}]")
            atoms.append(WeightedTerm(obj["weight"], Term(obj["term"])))
        return Weight(tuple(atoms))
    raise AgentInputError(f"unknown scoring-expression tag {tag!r}")


def parse_state_ref(value: object) -> StateRef:
    if isinstance(value, str):
        return HandleRef(value)
    tag, payload = _tag(value, "StateRef")
    if tag == "handle":
        return HandleRef(payload)
    if tag == "binding":
        return BindingRef(payload)
    raise AgentInputError(f"unknown state-reference tag {tag!r}")


def parse_documents(value: object) -> AllDocuments | Page:
    tag, payload = _tag(value, "DocumentSelector")
    if tag == "all_documents":
        obj = _object(payload, "all_documents")
        _keys(obj, set(), field="all_documents")
        return AllDocuments()
    if tag == "page":
        obj = _object(payload, "page")
        _keys(obj, {"limit"}, {"after"}, field="page")
        return Page(obj["limit"], obj.get("after"))
    raise AgentInputError(f"unknown document-selector tag {tag!r}")


def _parse_occurrence(value: object) -> First | Nth | AllOccurrences:
    tag, payload = _tag(value, "OccurrenceSelector")
    if tag in {"first", "all"}:
        obj = _object(payload, tag)
        _keys(obj, set(), field=tag)
        return First() if tag == "first" else AllOccurrences()
    if tag == "nth":
        return Nth(payload)
    raise AgentInputError(f"unknown occurrence-selector tag {tag!r}")


def parse_region(value: object) -> RegionSpec:
    tag, payload = _tag(value, "RegionSpec")
    if tag == "document":
        obj = _object(payload, "document")
        _keys(obj, set(), field="document")
        return Document()
    if tag == "range":
        obj = _object(payload, "range")
        _keys(obj, {"start", "end"}, field="range")
        return Range(obj["start"], obj["end"])
    if tag == "around":
        obj = _object(payload, "around")
        _keys(obj, {"anchor", "selector", "before", "after"}, field="around")
        return Around(
            parse_lexical(obj["anchor"]),
            _parse_occurrence(obj["selector"]),
            obj["before"],
            obj["after"],
        )
    raise AgentInputError(f"unknown region tag {tag!r}")


def _budget(value: object) -> ReadBudget:
    obj = _object(value, "ReadBudget")
    _keys(obj, {"max_output_codepoints", "max_evidence_count"}, field="ReadBudget")
    return ReadBudget(obj["max_output_codepoints"], obj["max_evidence_count"])


def parse_operation(value: object) -> Operation:
    obj = _object(value, "operation")
    op = obj.get("op")
    if not isinstance(op, str):
        raise AgentInputError("operation.op must be a string")
    op = op.upper()
    bind = obj.get("bind")
    if op == "FILTER":
        _keys(obj, {"op", "target", "condition"}, {"bind"}, field=op)
        return Filter(parse_state_ref(obj["target"]), parse_condition(obj["condition"]), bind)
    if op in {"INTERSECT", "UNION", "DIFFERENCE"}:
        _keys(obj, {"op", "left", "right"}, {"bind"}, field=op)
        args = (parse_state_ref(obj["left"]), parse_state_ref(obj["right"]), bind)
        return {"INTERSECT": Intersect, "UNION": Union, "DIFFERENCE": Difference}[op](*args)
    if op == "COUNT":
        _keys(obj, {"op", "state"}, field=op)
        return Count(parse_state_ref(obj["state"]))
    if op == "COUNT_DOCS":
        _keys(obj, {"op", "state", "condition"}, field=op)
        return CountDocs(parse_state_ref(obj["state"]), parse_condition(obj["condition"]))
    if op == "RANK":
        _keys(obj, {"op", "target", "scoring"}, {"bind"}, field=op)
        return Rank(parse_state_ref(obj["target"]), parse_scoring(obj["scoring"]), bind)
    if op == "TOPK":
        _keys(obj, {"op", "target", "k"}, {"bind"}, field=op)
        return TopK(parse_state_ref(obj["target"]), obj["k"], bind)
    if op == "RESTRICT":
        _keys(obj, {"op", "ranked", "allowed"}, {"bind"}, field=op)
        return Restrict(parse_state_ref(obj["ranked"]), parse_state_ref(obj["allowed"]), bind)
    if op == "AS_SET":
        _keys(obj, {"op", "target"}, {"bind"}, field=op)
        return AsSet(parse_state_ref(obj["target"]), bind)
    if op == "READ":
        _keys(obj, {"op", "target", "region", "budget"}, {"documents"}, field=op)
        tag, payload = _tag(_object(obj["target"], "READ target"), "READ target")
        if tag == "state":
            target = StateTarget(parse_state_ref(payload))
            if "documents" not in obj:
                raise AgentInputError("state READ requires documents")
            documents = parse_documents(obj["documents"])
        elif tag == "doc_key":
            target = DocKeyTarget(payload)
            if "documents" in obj:
                raise AgentInputError("DocKey READ forbids documents")
            documents = None
        else:
            raise AgentInputError(f"unknown READ target tag {tag!r}")
        return Read(target, parse_region(obj["region"]), _budget(obj["budget"]), documents)
    raise AgentInputError(f"unknown operation {op!r}")


def parse_operations(value: object) -> tuple[Operation, ...]:
    return tuple(parse_operation(item) for item in _array(value, "operations"))


def jsonable(value: Any) -> Any:
    """Convert typed client values into stable ordinary JSON values iteratively."""

    work: list[tuple[Any, bool]] = [(value, False)]
    built: list[Any] = []
    while work:
        current, expanded = work.pop()
        if isinstance(current, Enum):
            built.append(current.value)
            continue
        if is_dataclass(current):
            names = [item.name for item in fields(current)]
            if not expanded:
                work.append((current, True))
                work.extend((getattr(current, name), False) for name in reversed(names))
                continue
            children = built[-len(names) :] if names else []
            if names:
                del built[-len(names) :]
            built.append(dict(zip(names, children)))
            continue
        if isinstance(current, Mapping):
            items = list(current.items())
            if not expanded:
                work.append((current, True))
                work.extend((item, False) for _, item in reversed(items))
                continue
            children = built[-len(items) :] if items else []
            if items:
                del built[-len(items) :]
            built.append({str(key): child for (key, _), child in zip(items, children)})
            continue
        if isinstance(current, (tuple, list)):
            if not expanded:
                work.append((current, True))
                work.extend((item, False) for item in reversed(current))
                continue
            children = built[-len(current) :] if current else []
            if current:
                del built[-len(current) :]
            built.append(list(children))
            continue
        built.append(current)
    return built[0]


__all__ = ["AgentInputError", "jsonable", "parse_operation", "parse_operations"]
