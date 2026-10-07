package org.indexact.state;

import java.util.List;
import java.util.Objects;
import org.indexact.execution.RankedState;
import org.indexact.execution.SetState;
import org.indexact.execution.StateValue;

/** Immutable logical state with an evictable, deterministically recomputable physical value. */
public final class StateRecord {
    @FunctionalInterface
    public interface Recipe {
        StateValue compute() throws Exception;
    }

    private final String internalStateId;
    private final StateMetadata metadata;
    private final long creationSequence;
    private final List<StateRecord> dependencies;
    private final List<String> dependencyInternalIds;
    private final Recipe recipe;
    private volatile StateValue physicalValue;

    public StateRecord(
            String internalStateId,
            StateMetadata metadata,
            StateValue physicalValue,
            long creationSequence,
            List<StateRecord> dependencies,
            Recipe recipe) {
        this.internalStateId = Objects.requireNonNull(internalStateId, "internalStateId");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.physicalValue = requireMatching(physicalValue);
        this.creationSequence = creationSequence;
        this.dependencies = List.copyOf(dependencies);
        this.dependencyInternalIds = this.dependencies.stream()
                .map(StateRecord::internalStateId).toList();
        this.recipe = Objects.requireNonNull(recipe, "recipe");
    }

    public String internalStateId() { return internalStateId; }
    public StateMetadata metadata() { return metadata; }
    public long creationSequence() { return creationSequence; }
    public List<StateRecord> dependencies() { return dependencies; }
    public List<String> dependencyInternalIds() { return dependencyInternalIds; }

    public StateValue value() throws Exception {
        StateValue current = physicalValue;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = physicalValue;
            if (current == null) {
                current = requireMatching(recipe.compute());
                physicalValue = current;
            }
            return current;
        }
    }

    public boolean physicalResident() {
        return physicalValue != null;
    }

    /** Logical identity and handle lifetime are unaffected by physical eviction. */
    public synchronized void evictPhysical() {
        physicalValue = null;
    }

    private StateValue requireMatching(StateValue value) {
        Objects.requireNonNull(value, "physicalValue");
        boolean expectedSet = metadata.stateType().equals("set");
        if (!metadata.snapshotId().equals(value.snapshotId())
                || metadata.cardinality() != value.cardinality()
                || expectedSet && !(value instanceof SetState)
                || !expectedSet && !(value instanceof RankedState)) {
            throw new IllegalStateException("recomputed physical state violates immutable metadata");
        }
        return value;
    }
}
