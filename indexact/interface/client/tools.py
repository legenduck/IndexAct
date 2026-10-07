"""High-level typed tools over :mod:`client.protocol`.

``SessionTools`` is intentionally a thin convenience layer.  It never caches
document membership or materializes corpus text itself; every corpus operation is
one ``EXECUTE`` operation on the service.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence, TypeVar

from .dsl import (
    AsSet,
    Count,
    CountDocs,
    Difference,
    DocKeyTarget,
    DocumentSelector,
    Filter,
    Intersect,
    Operation,
    Rank,
    Read,
    ReadBudget,
    ReadTarget,
    RegionSpec,
    Restrict,
    ScoringExpression,
    StateRef,
    StateTarget,
    TextCondition,
    TopK,
    Union,
)
from .protocol import (
    CanonicalLineageNode,
    CountResult,
    ExecuteTrace,
    OpenSessionResult,
    OperationError,
    OperationResult,
    ProtocolClient,
    ReadBudgetExceeded,
    ReadSuccess,
    ReleaseStateResult,
    ServiceLimits,
    StateCreated,
    StateListPage,
    StateMetadata,
)


class OperationFailed(Exception):
    """The first failed operation in an otherwise valid EXECUTE trace."""

    def __init__(self, index: int, error: OperationError, trace: ExecuteTrace) -> None:
        self.index = index
        self.error = error
        self.trace = trace
        super().__init__(f"operation {index} failed with {error.code}: {error.message}")


ResultT = TypeVar("ResultT", bound=OperationResult)


@dataclass(slots=True)
class SessionTools:
    """A session-scoped facade covering every core and introspection operation."""

    client: ProtocolClient
    session_id: str
    corpus: StateMetadata
    service_limits: ServiceLimits
    protocol_version: str
    _closed: bool = False

    @classmethod
    def open(
        cls, client: ProtocolClient, snapshot_id: str, protocol_version: str = "3.4"
    ) -> "SessionTools":
        opened = client.open_session(snapshot_id, protocol_version)
        return cls.from_open_result(client, opened)

    @classmethod
    def from_open_result(
        cls, client: ProtocolClient, opened: OpenSessionResult
    ) -> "SessionTools":
        return cls(
            client,
            opened.session_id,
            opened.corpus,
            opened.service_limits,
            opened.protocol_version,
        )

    def __enter__(self) -> "SessionTools":
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self.close()

    def _require_open(self) -> None:
        if self._closed:
            raise RuntimeError("session has been closed")

    def execute(self, ops: Sequence[Operation]) -> ExecuteTrace:
        self._require_open()
        return self.client.execute(self.session_id, ops)

    def execute_one(self, operation: Operation) -> OperationResult:
        trace = self.execute((operation,))
        if trace.error is not None:
            assert trace.failed_operation_index is not None
            raise OperationFailed(trace.failed_operation_index, trace.error, trace)
        if len(trace.completed) != 1 or trace.completed[0].index != 0:
            raise RuntimeError("server returned an invalid one-operation success trace")
        return trace.completed[0].result

    def _state(self, operation: Operation) -> StateMetadata:
        result = self.execute_one(operation)
        if not isinstance(result, StateCreated):
            raise RuntimeError("server returned a non-state result for a state operation")
        return result.state

    def filter(
        self, target: StateRef, condition: TextCondition, *, bind: str | None = None
    ) -> StateMetadata:
        return self._state(Filter(target, condition, bind))

    def intersect(
        self, left: StateRef, right: StateRef, *, bind: str | None = None
    ) -> StateMetadata:
        return self._state(Intersect(left, right, bind))

    def union(
        self, left: StateRef, right: StateRef, *, bind: str | None = None
    ) -> StateMetadata:
        return self._state(Union(left, right, bind))

    def difference(
        self, left: StateRef, right: StateRef, *, bind: str | None = None
    ) -> StateMetadata:
        return self._state(Difference(left, right, bind))

    def count(self, state: StateRef) -> int:
        result = self.execute_one(Count(state))
        if not isinstance(result, CountResult):
            raise RuntimeError("server returned a non-count result for COUNT")
        return result.count

    def count_docs(self, state: StateRef, condition: TextCondition) -> int:
        result = self.execute_one(CountDocs(state, condition))
        if not isinstance(result, CountResult):
            raise RuntimeError("server returned a non-count result for COUNT_DOCS")
        return result.count

    def rank(
        self, target: StateRef, scoring: ScoringExpression, *, bind: str | None = None
    ) -> StateMetadata:
        return self._state(Rank(target, scoring, bind))

    def topk(
        self, target: StateRef, k: int, *, bind: str | None = None
    ) -> StateMetadata:
        return self._state(TopK(target, k, bind))

    def restrict(
        self,
        ranked: StateRef,
        allowed: StateRef,
        *,
        bind: str | None = None,
    ) -> StateMetadata:
        return self._state(Restrict(ranked, allowed, bind))

    def as_set(self, target: StateRef, *, bind: str | None = None) -> StateMetadata:
        return self._state(AsSet(target, bind))

    def read(
        self,
        target: ReadTarget,
        region: RegionSpec,
        budget: ReadBudget,
        *,
        documents: DocumentSelector | None = None,
    ) -> ReadSuccess | ReadBudgetExceeded:
        result = self.execute_one(Read(target, region, budget, documents))
        if not isinstance(result, (ReadSuccess, ReadBudgetExceeded)):
            raise RuntimeError("server returned a non-READ result for READ")
        return result

    def read_state(
        self,
        state: StateRef,
        documents: DocumentSelector,
        region: RegionSpec,
        budget: ReadBudget,
    ) -> ReadSuccess | ReadBudgetExceeded:
        return self.read(StateTarget(state), region, budget, documents=documents)

    def read_doc(
        self, doc_key: str, region: RegionSpec, budget: ReadBudget
    ) -> ReadSuccess | ReadBudgetExceeded:
        return self.read(DocKeyTarget(doc_key), region, budget)

    def list_states(self, limit: int, after: str | None = None) -> StateListPage:
        self._require_open()
        return self.client.list_states(self.session_id, limit, after)

    def get_lineage_node(self, lineage_id: str) -> CanonicalLineageNode:
        self._require_open()
        return self.client.get_lineage_node(self.session_id, lineage_id)

    def release_state(self, handle: str) -> ReleaseStateResult:
        self._require_open()
        return self.client.release_state(self.session_id, handle)

    def close(self) -> None:
        if not self._closed:
            self.client.close_session(self.session_id)
            self._closed = True


__all__ = ["OperationFailed", "SessionTools"]
