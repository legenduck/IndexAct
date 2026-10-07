package org.indexact.execution;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.indexact.analysis.ContractAnalyzer;
import org.indexact.expression.ScoringExpression;
import org.indexact.index.Snapshot;
import org.indexact.index.SnapshotBuilder;

/** Logical RANK with postings-driven materialization and bounded-heap TOPK. */
public final class RankOperations implements AutoCloseable {
    private final Snapshot snapshot;
    private final Bm25Parameters bm25;
    private final ContractAnalyzer analyzer = new ContractAnalyzer();

    public RankOperations(Snapshot snapshot) {
        this(snapshot, Bm25Parameters.WIKIPEDIA_18);
    }

    public RankOperations(Snapshot snapshot, Bm25Parameters bm25) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.bm25 = Objects.requireNonNull(bm25, "bm25");
    }

    public RankedState rank(SetState target, ScoringExpression expression) throws IOException {
        SetState bound = SetState.fromMembership(membership(target));
        Objects.requireNonNull(expression, "scoring expression");
        ScoringPlan plan = ScoringPlan.build(snapshot, analyzer, expression, bm25);
        return RankedState.deferred(bound, plan);
    }

    public RankedState topK(RankedState target, long k) throws IOException {
        requireRanked(target);
        if (k < 0 || k > SnapshotBuilder.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("TOPK k is outside the public integer domain");
        }
        int size = Math.toIntExact(Math.min(k, target.cardinality()));
        if (target.scoringPlan() != null) {
            ScoringPlan.Materialization prefix = target.scoringPlan()
                    .topK(target.membershipState().physicalMembership(), size);
            return new RankedState(snapshot.snapshotId(), prefix.order(), prefix.scores());
        }
        return new RankedState(
                snapshot.snapshotId(),
                target.order().subList(0, size),
                target.scores().subList(0, size));
    }

    public RankedState restrict(RankedState ranked, SetState allowed) {
        requireRanked(ranked);
        StateMembership allowedMembership = membership(allowed);
        if (ranked.scoringPlan() != null) {
            StateMembership restricted = ranked.membershipState()
                    .physicalMembership()
                    .intersect(allowedMembership);
            return RankedState.deferred(SetState.fromMembership(restricted), ranked.scoringPlan());
        }
        List<String> order = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int index = 0; index < ranked.order().size(); index++) {
            String docKey = ranked.order().get(index);
            if (allowedMembership.containsGlobal(snapshot.internalDocumentId(docKey))) {
                order.add(docKey);
                scores.add(ranked.scores().get(index));
            }
        }
        return new RankedState(snapshot.snapshotId(), order, scores);
    }

    public SetState asSet(RankedState target) {
        requireRanked(target);
        if (target.membershipState().hasPhysicalMembership()) {
            return target.membershipState();
        }
        return SetState.fromMembership(StateMembership.fromDocKeys(snapshot, target.members()));
    }

    private StateMembership membership(SetState state) {
        Objects.requireNonNull(state, "state");
        if (!snapshot.snapshotId().equals(state.snapshotId())) {
            throw new IllegalArgumentException("SetState does not belong to this snapshot");
        }
        return state.hasPhysicalMembership()
                ? state.physicalMembership()
                : StateMembership.fromDocKeys(snapshot, state.members());
    }

    private void requireRanked(RankedState state) {
        Objects.requireNonNull(state, "state");
        if (!snapshot.snapshotId().equals(state.snapshotId())) {
            throw new IllegalArgumentException("RankedState does not belong to this snapshot");
        }
        SetState membership = state.membershipState();
        if (membership.hasPhysicalMembership()
                && membership.physicalMembership().belongsTo(snapshot)) {
            return; // Membership was validated when bound; do not expand every DocKey.
        }
        for (String docKey : state.members()) {
            snapshot.internalDocumentId(docKey);
        }
    }

    @Override
    public void close() {
        analyzer.close();
    }
}
