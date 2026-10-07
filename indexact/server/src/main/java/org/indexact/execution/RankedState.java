package org.indexact.execution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable logical RankedRef with optional, evictable-style deferred physical order. */
public final class RankedState implements StateValue {
    private final String snapshotId;
    private final SetState membership;
    private final ScoringPlan plan;
    private volatile ScoringPlan.Materialization materialized;

    public RankedState(String snapshotId, List<String> order, List<Double> scores) {
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        List<String> exactOrder = List.copyOf(Objects.requireNonNull(order, "order"));
        List<Double> exactScores = List.copyOf(Objects.requireNonNull(scores, "scores"));
        validate(exactOrder, exactScores);
        this.membership = new SetState(snapshotId, exactOrder);
        this.plan = null;
        this.materialized = new ScoringPlan.Materialization(exactOrder, exactScores);
    }

    private RankedState(SetState membership, ScoringPlan plan) {
        this.snapshotId = membership.snapshotId();
        this.membership = membership;
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    static RankedState deferred(SetState membership, ScoringPlan plan) {
        return new RankedState(membership, plan);
    }

    public String snapshotId() {
        return snapshotId;
    }

    public long cardinality() {
        return membership.cardinality();
    }

    public Set<String> members() {
        return membership.members();
    }

    public List<String> order() {
        return materialization().order();
    }

    public List<Double> scores() {
        return materialization().scores();
    }

    public double scoreFor(String docKey) {
        int index = order().indexOf(docKey);
        if (index < 0) {
            throw new java.util.NoSuchElementException("document is not ranked: " + docKey);
        }
        return scores().get(index);
    }

    public boolean isMaterialized() {
        return materialized != null;
    }

    SetState membershipState() {
        return membership;
    }

    ScoringPlan scoringPlan() {
        return plan;
    }

    private ScoringPlan.Materialization materialization() {
        ScoringPlan.Materialization current = materialized;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = materialized;
            if (current == null) {
                try {
                    current = plan.materialize(membership.physicalMembership());
                } catch (IOException error) {
                    throw new UncheckedIOException("rank materialization failed", error);
                }
                validate(current.order(), current.scores());
                materialized = current;
            }
            return current;
        }
    }

    private static void validate(List<String> order, List<Double> scores) {
        if (order.size() != scores.size() || new HashSet<>(order).size() != order.size()) {
            throw new IllegalArgumentException("ranked order/scores are not one-to-one");
        }
        for (double score : scores) {
            if (!Double.isFinite(score) || score < 0.0) {
                throw new IllegalArgumentException("ranked score must be finite and non-negative");
            }
        }
    }
}
