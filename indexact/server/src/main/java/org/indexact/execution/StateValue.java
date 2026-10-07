package org.indexact.execution;

/** Complete immutable logical denotation behind one public state handle. */
public sealed interface StateValue permits SetState, RankedState {
    String snapshotId();

    long cardinality();
}
