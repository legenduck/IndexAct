"""Typed request DSL for the IndexAct wire API.

The classes in this module are deliberately small immutable value objects.  Their
``to_wire`` methods produce the tagged JSON shapes accepted by the service.
This module never normalizes text:
TERM surfaces and DocKeys are retained scalar-for-scalar.
"""

from __future__ import annotations

from dataclasses import dataclass
from math import isfinite
import re
from typing import Any, ClassVar, Mapping, Sequence, TypeAlias


MAX_SAFE_INTEGER = 9_007_199_254_740_991

JSONValue: TypeAlias = (
    None | bool | int | float | str | list["JSONValue"] | dict[str, "JSONValue"]
)
WireObject: TypeAlias = dict[str, JSONValue]

_BINDING_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,63}\Z")
_HANDLE_RE = re.compile(r"(?:s|r)([1-9][0-9]{0,15})\Z")
_READ_CURSOR_RE = re.compile(r"cur-[0-9a-f]{64}\Z")


def _is_noncharacter(codepoint: int) -> bool:
    return 0xFDD0 <= codepoint <= 0xFDEF or (
        codepoint <= 0x10FFFF and codepoint & 0xFFFF in (0xFFFE, 0xFFFF)
    )


def validate_raw_text(value: object, field: str = "string") -> str:
    """Validate a Unicode scalar string without altering it."""

    if not isinstance(value, str):
        raise TypeError(f"{field} must be a string")
    for char in value:
        codepoint = ord(char)
        if 0xD800 <= codepoint <= 0xDFFF:
            raise ValueError(f"{field} must contain only Unicode scalar values")
    return value


def validate_lineage_string(value: object, field: str = "string") -> str:
    value = validate_raw_text(value, field)
    if any(_is_noncharacter(ord(char)) for char in value):
        raise ValueError(f"{field} must not contain a Unicode noncharacter")
    return value


def validate_non_negative_int(value: object, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError(f"{field} must be an integer")
    if value < 0 or value > MAX_SAFE_INTEGER:
        raise ValueError(f"{field} must be in [0, {MAX_SAFE_INTEGER}]")
    return value


def validate_positive_int(value: object, field: str) -> int:
    value = validate_non_negative_int(value, field)
    if value == 0:
        raise ValueError(f"{field} must be positive")
    return value


def validate_finite_non_negative_float(value: object, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise TypeError(f"{field} must be a number")
    try:
        result = float(value)
    except OverflowError as error:
        raise ValueError(f"{field} must be finite and non-negative") from error
    if value < 0 or not isfinite(result):
        raise ValueError(f"{field} must be finite and non-negative")
    # The public semantic value of either zero sign is positive zero.
    return 0.0 if result == 0.0 else result


def validate_binding_name(value: object, field: str = "binding") -> str:
    value = validate_lineage_string(value, field)
    if value == "CORPUS" or _BINDING_RE.fullmatch(value) is None:
        raise ValueError(f"{field} is not a valid BindingName")
    return value


def validate_state_handle(value: object, field: str = "handle") -> str:
    value = validate_lineage_string(value, field)
    if value == "CORPUS":
        return value
    match = _HANDLE_RE.fullmatch(value)
    if match is None or int(match.group(1)) > MAX_SAFE_INTEGER:
        raise ValueError(f"{field} is not a valid StateHandle")
    return value


def _tuple_of(values: Sequence[Any], expected: type, field: str, minimum: int) -> tuple[Any, ...]:
    if isinstance(values, (str, bytes)) or not isinstance(values, Sequence):
        raise TypeError(f"{field} must be a sequence")
    result = tuple(values)
    if len(result) < minimum:
        raise ValueError(f"{field} must contain at least {minimum} item(s)")
    if not all(isinstance(item, expected) for item in result):
        raise TypeError(f"every {field} item must be {expected.__name__}")
    return result


class WireValue:
    """A value that has an approved JSON representation."""

    def to_wire(self) -> WireObject:
        raise NotImplementedError


class TextCondition(WireValue):
    pass


class LexicalExpression(TextCondition):
    pass


class ScoringExpression(WireValue):
    pass


@dataclass(frozen=True, slots=True)
class Term(LexicalExpression, ScoringExpression):
    surface: str

    def __post_init__(self) -> None:
        validate_lineage_string(self.surface, "TERM surface")

    def to_wire(self) -> WireObject:
        return {"term": self.surface}


@dataclass(frozen=True, slots=True)
class AnyOf(LexicalExpression):
    children: tuple[LexicalExpression, ...]

    def __post_init__(self) -> None:
        object.__setattr__(
            self, "children", _tuple_of(self.children, LexicalExpression, "ANY_OF children", 2)
        )

    def to_wire(self) -> WireObject:
        return _text_expression_to_wire(self)


@dataclass(frozen=True, slots=True)
class Phrase(LexicalExpression):
    children: tuple[LexicalExpression, ...]

    def __post_init__(self) -> None:
        object.__setattr__(
            self, "children", _tuple_of(self.children, LexicalExpression, "PHRASE children", 2)
        )

    def to_wire(self) -> WireObject:
        return _text_expression_to_wire(self)


@dataclass(frozen=True, slots=True)
class Near(LexicalExpression):
    children: tuple[LexicalExpression, ...]
    ordered: bool
    max_gaps: int

    def __post_init__(self) -> None:
        object.__setattr__(
            self, "children", _tuple_of(self.children, LexicalExpression, "NEAR children", 2)
        )
        if not isinstance(self.ordered, bool):
            raise TypeError("ordered must be a bool")
        validate_non_negative_int(self.max_gaps, "max_gaps")

    def to_wire(self) -> WireObject:
        return _text_expression_to_wire(self)


@dataclass(frozen=True, slots=True)
class And(TextCondition):
    children: tuple[TextCondition, ...]

    def __post_init__(self) -> None:
        object.__setattr__(
            self, "children", _tuple_of(self.children, TextCondition, "AND children", 2)
        )

    def to_wire(self) -> WireObject:
        return _text_expression_to_wire(self)


@dataclass(frozen=True, slots=True)
class Or(TextCondition):
    children: tuple[TextCondition, ...]

    def __post_init__(self) -> None:
        object.__setattr__(
            self, "children", _tuple_of(self.children, TextCondition, "OR children", 2)
        )

    def to_wire(self) -> WireObject:
        return _text_expression_to_wire(self)


@dataclass(frozen=True, slots=True)
class Not(TextCondition):
    child: TextCondition

    def __post_init__(self) -> None:
        if not isinstance(self.child, TextCondition):
            raise TypeError("NOT child must be a TextCondition")

    def to_wire(self) -> WireObject:
        return _text_expression_to_wire(self)


def _text_expression_to_wire(expression: TextCondition) -> WireObject:
    work: list[tuple[TextCondition, bool]] = [(expression, False)]
    built: list[WireObject] = []
    while work:
        current, expanded = work.pop()
        children: tuple[TextCondition, ...]
        if isinstance(current, (AnyOf, Phrase, Near, And, Or)):
            children = current.children
        elif isinstance(current, Not):
            children = (current.child,)
        else:
            children = ()
        if not expanded:
            work.append((current, True))
            work.extend((child, False) for child in reversed(children))
            continue
        child_values = built[-len(children) :] if children else []
        if children:
            del built[-len(children) :]
        if isinstance(current, Term):
            value: WireObject = {"term": current.surface}
        elif isinstance(current, AnyOf):
            value = {"any_of": child_values}
        elif isinstance(current, Phrase):
            value = {"phrase": child_values}
        elif isinstance(current, Near):
            value = {
                "near": {
                    "children": child_values,
                    "ordered": current.ordered,
                    "max_gaps": current.max_gaps,
                }
            }
        elif isinstance(current, And):
            value = {"and": child_values}
        elif isinstance(current, Or):
            value = {"or": child_values}
        elif isinstance(current, Not):
            value = {"not": child_values[0]}
        else:  # pragma: no cover - closed DSL hierarchy
            raise TypeError("unsupported text expression")
        built.append(value)
    if len(built) != 1:  # pragma: no cover
        raise AssertionError("expression serialization did not produce one value")
    return built[0]


@dataclass(frozen=True, slots=True)
class Combine(ScoringExpression):
    terms: tuple[Term, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "terms", _tuple_of(self.terms, Term, "COMBINE terms", 1))

    def to_wire(self) -> WireObject:
        return {"combine": [term.to_wire() for term in self.terms]}


@dataclass(frozen=True, slots=True)
class WeightedTerm:
    weight: float
    term: Term

    def __post_init__(self) -> None:
        object.__setattr__(
            self, "weight", validate_finite_non_negative_float(self.weight, "weight")
        )
        if not isinstance(self.term, Term):
            raise TypeError("weighted scoring atoms must be TERM")

    def to_wire(self) -> WireObject:
        return {"weight": self.weight, "term": self.term.surface}


@dataclass(frozen=True, slots=True)
class Weight(ScoringExpression):
    atoms: tuple[WeightedTerm, ...]

    def __post_init__(self) -> None:
        object.__setattr__(
            self, "atoms", _tuple_of(self.atoms, WeightedTerm, "WEIGHT atoms", 1)
        )

    def to_wire(self) -> WireObject:
        return {"weight": [atom.to_wire() for atom in self.atoms]}


class StateRef(WireValue):
    pass


@dataclass(frozen=True, slots=True)
class HandleRef(StateRef):
    handle: str

    def __post_init__(self) -> None:
        validate_state_handle(self.handle)

    def to_wire(self) -> WireObject:
        return {"handle": self.handle}


@dataclass(frozen=True, slots=True)
class BindingRef(StateRef):
    binding: str

    def __post_init__(self) -> None:
        validate_binding_name(self.binding)

    def to_wire(self) -> WireObject:
        return {"binding": self.binding}


CORPUS = HandleRef("CORPUS")


class DocumentSelector(WireValue):
    pass


@dataclass(frozen=True, slots=True)
class AllDocuments(DocumentSelector):
    def to_wire(self) -> WireObject:
        return {"all_documents": {}}


@dataclass(frozen=True, slots=True)
class Page(DocumentSelector):
    limit: int
    after: str | None = None

    def __post_init__(self) -> None:
        validate_positive_int(self.limit, "PAGE limit")
        if self.after is not None:
            cursor = validate_lineage_string(self.after, "ReadCursor")
            if _READ_CURSOR_RE.fullmatch(cursor) is None:
                raise ValueError("after is not a valid ReadCursor")

    def to_wire(self) -> WireObject:
        page: WireObject = {"limit": self.limit}
        if self.after is not None:
            page["after"] = self.after
        return {"page": page}


@dataclass(frozen=True, slots=True)
class ReadBudget(WireValue):
    max_output_codepoints: int
    max_evidence_count: int

    def __post_init__(self) -> None:
        validate_non_negative_int(self.max_output_codepoints, "max_output_codepoints")
        validate_non_negative_int(self.max_evidence_count, "max_evidence_count")

    def to_wire(self) -> WireObject:
        return {
            "max_output_codepoints": self.max_output_codepoints,
            "max_evidence_count": self.max_evidence_count,
        }


class OccurrenceSelector(WireValue):
    pass


@dataclass(frozen=True, slots=True)
class First(OccurrenceSelector):
    def to_wire(self) -> WireObject:
        return {"first": {}}


@dataclass(frozen=True, slots=True)
class Nth(OccurrenceSelector):
    n: int

    def __post_init__(self) -> None:
        validate_positive_int(self.n, "NTH n")

    def to_wire(self) -> WireObject:
        return {"nth": self.n}


@dataclass(frozen=True, slots=True)
class AllOccurrences(OccurrenceSelector):
    def to_wire(self) -> WireObject:
        return {"all": {}}


class RegionSpec(WireValue):
    pass


@dataclass(frozen=True, slots=True)
class Document(RegionSpec):
    def to_wire(self) -> WireObject:
        return {"document": {}}


@dataclass(frozen=True, slots=True)
class Range(RegionSpec):
    start: int
    end: int

    def __post_init__(self) -> None:
        validate_non_negative_int(self.start, "RANGE start")
        validate_non_negative_int(self.end, "RANGE end")
        if self.start > self.end:
            raise ValueError("RANGE start must not exceed end")

    def to_wire(self) -> WireObject:
        return {"range": {"start": self.start, "end": self.end}}


@dataclass(frozen=True, slots=True)
class Around(RegionSpec):
    anchor: LexicalExpression
    selector: OccurrenceSelector
    before: int
    after: int

    def __post_init__(self) -> None:
        if not isinstance(self.anchor, LexicalExpression):
            raise TypeError("AROUND anchor must be a LexicalExpression")
        if not isinstance(self.selector, OccurrenceSelector):
            raise TypeError("AROUND selector must be an OccurrenceSelector")
        validate_non_negative_int(self.before, "AROUND before")
        validate_non_negative_int(self.after, "AROUND after")

    def to_wire(self) -> WireObject:
        return {
            "around": {
                "anchor": self.anchor.to_wire(),
                "selector": self.selector.to_wire(),
                "before": self.before,
                "after": self.after,
            }
        }


DOCUMENT = Document()
ALL_DOCUMENTS = AllDocuments()
FIRST = First()
ALL_OCCURRENCES = AllOccurrences()


class ReadTarget(WireValue):
    pass


@dataclass(frozen=True, slots=True)
class StateTarget(ReadTarget):
    state: StateRef

    def __post_init__(self) -> None:
        if not isinstance(self.state, StateRef):
            raise TypeError("state target must contain a StateRef")

    def to_wire(self) -> WireObject:
        return {"state": self.state.to_wire()}


@dataclass(frozen=True, slots=True)
class DocKeyTarget(ReadTarget):
    doc_key: str

    def __post_init__(self) -> None:
        validate_raw_text(self.doc_key, "DocKey")

    def to_wire(self) -> WireObject:
        return {"doc_key": self.doc_key}


class Operation(WireValue):
    op: ClassVar[str]


def _validate_bind(bind: str | None, state_producing: bool) -> None:
    if bind is not None:
        if not state_producing:
            raise ValueError("bind is allowed only on state-producing operations")
        validate_binding_name(bind, "bind")


def _add_bind(wire: WireObject, bind: str | None) -> WireObject:
    if bind is not None:
        wire["bind"] = bind
    return wire


@dataclass(frozen=True, slots=True)
class Filter(Operation):
    op: ClassVar[str] = "FILTER"
    target: StateRef
    condition: TextCondition
    bind: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.target, StateRef) or not isinstance(self.condition, TextCondition):
            raise TypeError("FILTER requires a StateRef and TextCondition")
        _validate_bind(self.bind, True)

    def to_wire(self) -> WireObject:
        return _add_bind(
            {"op": self.op, "target": self.target.to_wire(), "condition": self.condition.to_wire()},
            self.bind,
        )


@dataclass(frozen=True, slots=True)
class _BinarySetOperation(Operation):
    left: StateRef
    right: StateRef
    bind: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.left, StateRef) or not isinstance(self.right, StateRef):
            raise TypeError(f"{self.op} requires two StateRefs")
        _validate_bind(self.bind, True)

    def to_wire(self) -> WireObject:
        return _add_bind(
            {"op": self.op, "left": self.left.to_wire(), "right": self.right.to_wire()},
            self.bind,
        )


@dataclass(frozen=True, slots=True)
class Intersect(_BinarySetOperation):
    op: ClassVar[str] = "INTERSECT"


@dataclass(frozen=True, slots=True)
class Union(_BinarySetOperation):
    op: ClassVar[str] = "UNION"


@dataclass(frozen=True, slots=True)
class Difference(_BinarySetOperation):
    op: ClassVar[str] = "DIFFERENCE"


@dataclass(frozen=True, slots=True)
class Count(Operation):
    op: ClassVar[str] = "COUNT"
    state: StateRef

    def __post_init__(self) -> None:
        if not isinstance(self.state, StateRef):
            raise TypeError("COUNT requires a StateRef")

    def to_wire(self) -> WireObject:
        return {"op": self.op, "state": self.state.to_wire()}


@dataclass(frozen=True, slots=True)
class CountDocs(Operation):
    op: ClassVar[str] = "COUNT_DOCS"
    state: StateRef
    condition: TextCondition

    def __post_init__(self) -> None:
        if not isinstance(self.state, StateRef) or not isinstance(self.condition, TextCondition):
            raise TypeError("COUNT_DOCS requires a StateRef and TextCondition")

    def to_wire(self) -> WireObject:
        return {
            "op": self.op,
            "state": self.state.to_wire(),
            "condition": self.condition.to_wire(),
        }


@dataclass(frozen=True, slots=True)
class Rank(Operation):
    op: ClassVar[str] = "RANK"
    target: StateRef
    scoring: ScoringExpression
    bind: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.target, StateRef) or not isinstance(
            self.scoring, ScoringExpression
        ):
            raise TypeError("RANK requires a StateRef and ScoringExpression")
        _validate_bind(self.bind, True)

    def to_wire(self) -> WireObject:
        return _add_bind(
            {"op": self.op, "target": self.target.to_wire(), "scoring": self.scoring.to_wire()},
            self.bind,
        )


@dataclass(frozen=True, slots=True)
class TopK(Operation):
    op: ClassVar[str] = "TOPK"
    target: StateRef
    k: int
    bind: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.target, StateRef):
            raise TypeError("TOPK requires a StateRef")
        validate_non_negative_int(self.k, "TOPK k")
        _validate_bind(self.bind, True)

    def to_wire(self) -> WireObject:
        return _add_bind(
            {"op": self.op, "target": self.target.to_wire(), "k": self.k}, self.bind
        )


@dataclass(frozen=True, slots=True)
class Restrict(Operation):
    op: ClassVar[str] = "RESTRICT"
    ranked: StateRef
    allowed: StateRef
    bind: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.ranked, StateRef) or not isinstance(self.allowed, StateRef):
            raise TypeError("RESTRICT requires two StateRefs")
        _validate_bind(self.bind, True)

    def to_wire(self) -> WireObject:
        return _add_bind(
            {
                "op": self.op,
                "ranked": self.ranked.to_wire(),
                "allowed": self.allowed.to_wire(),
            },
            self.bind,
        )


@dataclass(frozen=True, slots=True)
class AsSet(Operation):
    op: ClassVar[str] = "AS_SET"
    target: StateRef
    bind: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.target, StateRef):
            raise TypeError("AS_SET requires a StateRef")
        _validate_bind(self.bind, True)

    def to_wire(self) -> WireObject:
        return _add_bind({"op": self.op, "target": self.target.to_wire()}, self.bind)


@dataclass(frozen=True, slots=True)
class Read(Operation):
    op: ClassVar[str] = "READ"
    target: ReadTarget
    region: RegionSpec
    budget: ReadBudget
    documents: DocumentSelector | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.target, ReadTarget):
            raise TypeError("READ target must be a ReadTarget")
        if not isinstance(self.region, RegionSpec) or not isinstance(self.budget, ReadBudget):
            raise TypeError("READ requires a RegionSpec and ReadBudget")
        if isinstance(self.target, StateTarget):
            if self.documents is None:
                raise ValueError("state READ requires a DocumentSelector")
            if not isinstance(self.documents, DocumentSelector):
                raise TypeError("documents must be a DocumentSelector")
            if isinstance(self.region, Range):
                raise TypeError("RANGE is valid only with a DocKey target")
        elif self.documents is not None:
            raise ValueError("DocKey READ forbids documents")

    def to_wire(self) -> WireObject:
        wire: WireObject = {"op": self.op, "target": self.target.to_wire()}
        if self.documents is not None:
            wire["documents"] = self.documents.to_wire()
        wire["region"] = self.region.to_wire()
        wire["budget"] = self.budget.to_wire()
        return wire


# Compact factory functions make agent-tool call sites readable while preserving
# the explicit immutable model classes above.
def term(surface: str) -> Term:
    return Term(surface)


def any_of(*children: LexicalExpression) -> AnyOf:
    return AnyOf(children)


def phrase(*children: LexicalExpression) -> Phrase:
    return Phrase(children)


def near(
    *children: LexicalExpression, ordered: bool, max_gaps: int
) -> Near:
    return Near(children, ordered=ordered, max_gaps=max_gaps)


def and_(*children: TextCondition) -> And:
    return And(children)


def or_(*children: TextCondition) -> Or:
    return Or(children)


def not_(child: TextCondition) -> Not:
    return Not(child)


def combine(*terms: Term) -> Combine:
    return Combine(terms)


def weight(*atoms: tuple[float, Term] | WeightedTerm) -> Weight:
    weighted_items: list[WeightedTerm] = []
    for atom in atoms:
        if isinstance(atom, WeightedTerm):
            weighted_items.append(atom)
            continue
        if not isinstance(atom, tuple) or len(atom) != 2:
            raise TypeError("each WEIGHT factory item must be WeightedTerm or (weight, Term)")
        weighted_items.append(WeightedTerm(atom[0], atom[1]))
    weighted = tuple(weighted_items)
    return Weight(weighted)


def handle(value: str) -> HandleRef:
    return HandleRef(value)


def binding(value: str) -> BindingRef:
    return BindingRef(value)


def state_target(state: StateRef) -> StateTarget:
    return StateTarget(state)


def doc_key_target(doc_key: str) -> DocKeyTarget:
    return DocKeyTarget(doc_key)


def copy_json(value: JSONValue) -> JSONValue:
    """Copy a JSON tree while validating its scalar-only public domain.

    Used for lineage/error diagnostic payloads.  Mapping keys are retained
    exactly; this function performs no Unicode normalization or key sorting.
    """

    work: list[tuple[object, bool]] = [(value, False)]
    built: list[JSONValue] = []
    while work:
        current, expanded = work.pop()
        if not expanded and isinstance(current, (list, Mapping)):
            work.append((current, True))
            children = list(current.values()) if isinstance(current, Mapping) else current
            work.extend((item, False) for item in reversed(children))
            continue
        if current is None or isinstance(current, (bool, str)):
            if isinstance(current, str):
                validate_raw_text(current)
            built.append(current)
        elif isinstance(current, int):
            if current < -MAX_SAFE_INTEGER or current > MAX_SAFE_INTEGER:
                raise ValueError("JSON integer is outside the interoperable safe range")
            built.append(current)
        elif isinstance(current, float):
            if not isfinite(current):
                raise ValueError("JSON number must be finite")
            built.append(0.0 if current == 0.0 else current)
        elif isinstance(current, list):
            length = len(current)
            children = built[-length:] if length else []
            if length:
                del built[-length:]
            built.append(list(children))
        elif isinstance(current, Mapping):
            keys = list(current.keys())
            for key in keys:
                validate_raw_text(key, "JSON property name")
            children = built[-len(keys) :] if keys else []
            if keys:
                del built[-len(keys) :]
            built.append(dict(zip(keys, children)))
        else:
            raise TypeError("value is not JSON-compatible")
    return built[0]


__all__ = [
    "ALL_DOCUMENTS",
    "ALL_OCCURRENCES",
    "CORPUS",
    "DOCUMENT",
    "FIRST",
    "MAX_SAFE_INTEGER",
    "AllDocuments",
    "AllOccurrences",
    "And",
    "AnyOf",
    "Around",
    "AsSet",
    "BindingRef",
    "Combine",
    "Count",
    "CountDocs",
    "Difference",
    "DocKeyTarget",
    "Document",
    "DocumentSelector",
    "Filter",
    "First",
    "HandleRef",
    "Intersect",
    "LexicalExpression",
    "Near",
    "Not",
    "Nth",
    "OccurrenceSelector",
    "Operation",
    "Or",
    "Page",
    "Phrase",
    "Range",
    "Rank",
    "Read",
    "ReadBudget",
    "ReadTarget",
    "RegionSpec",
    "Restrict",
    "ScoringExpression",
    "StateRef",
    "StateTarget",
    "Term",
    "TextCondition",
    "TopK",
    "Union",
    "Weight",
    "WeightedTerm",
    "and_",
    "any_of",
    "binding",
    "combine",
    "doc_key_target",
    "handle",
    "near",
    "not_",
    "or_",
    "phrase",
    "state_target",
    "term",
    "weight",
]
