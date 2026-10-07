package org.indexact.execution;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/** Immutable physical denotation of a SetRef; membership is unordered. */
public final class SetState implements StateValue {
    private final String snapshotId;
    private final Set<String> detachedMembers;
    private final StateMembership membership;

    public SetState(String snapshotId, Collection<String> members) {
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        this.detachedMembers = Set.copyOf(Objects.requireNonNull(members, "members"));
        this.membership = null;
    }

    private SetState(StateMembership membership) {
        this.snapshotId = membership.snapshotId();
        this.detachedMembers = null;
        this.membership = membership;
    }

    public static SetState fromMembership(StateMembership membership) {
        return new SetState(Objects.requireNonNull(membership, "membership"));
    }

    public String snapshotId() {
        return snapshotId;
    }

    public Set<String> members() {
        return membership == null ? detachedMembers : membership.docKeys();
    }

    public java.util.List<String> orderedMembers() {
        return membership == null
                ? detachedMembers.stream()
                        .sorted(org.indexact.index.SnapshotBuilder.CODE_POINT_ORDER)
                        .toList()
                : membership.orderedDocKeys();
    }

    public StateMembership physicalMembership() {
        if (membership == null) {
            throw new IllegalStateException("detached SetState has not been bound to a snapshot");
        }
        return membership;
    }

    public boolean hasPhysicalMembership() {
        return membership != null;
    }

    public long cardinality() {
        return membership == null ? detachedMembers.size() : membership.cardinality();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SetState state
                && snapshotId.equals(state.snapshotId)
                && members().equals(state.members());
    }

    @Override
    public int hashCode() {
        return Objects.hash(snapshotId, members());
    }

    @Override
    public String toString() {
        return "SetState[snapshotId=" + snapshotId + ", members=" + members() + "]";
    }
}
