package org.indexact.session;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.indexact.index.Snapshot;
import org.indexact.protocol.ServiceLimits;
import org.indexact.state.CanonicalLineageNode;
import org.indexact.state.StateRecord;

/** Mutable session shell whose published records and denotations are immutable. */
public final class SessionRecord {
    public record ReadCursor(String targetHandle, long position) {}

    public record StateCursor(long creationSequence) {}

    private final String sessionId;
    private final String protocolVersion;
    private final Snapshot snapshot;
    private final ServiceLimits limits;
    private final ReentrantLock requestLock;
    private Map<String, StateRecord> states;
    private Map<String, CanonicalLineageNode> lineageNodes;
    private Map<String, ReadCursor> readCursors;
    private Map<String, String> readCursorTokens;
    private Map<String, StateCursor> stateCursors;
    private Map<Long, String> stateCursorTokens;
    private long handleSequence;
    private long creationSequence;
    private long internalSequence;

    public SessionRecord(
            String sessionId,
            String protocolVersion,
            Snapshot snapshot,
            ServiceLimits limits,
            StateRecord corpus,
            CanonicalLineageNode corpusLineage) {
        this(
                sessionId,
                protocolVersion,
                snapshot,
                limits,
                new ReentrantLock(),
                new LinkedHashMap<>(Map.of("CORPUS", corpus)),
                new HashMap<>(Map.of(corpusLineage.id(), corpusLineage)),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                0,
                0,
                0);
    }

    private SessionRecord(
            String sessionId,
            String protocolVersion,
            Snapshot snapshot,
            ServiceLimits limits,
            ReentrantLock requestLock,
            Map<String, StateRecord> states,
            Map<String, CanonicalLineageNode> lineageNodes,
            Map<String, ReadCursor> readCursors,
            Map<String, String> readCursorTokens,
            Map<String, StateCursor> stateCursors,
            Map<Long, String> stateCursorTokens,
            long handleSequence,
            long creationSequence,
            long internalSequence) {
        this.sessionId = sessionId;
        this.protocolVersion = protocolVersion;
        this.snapshot = snapshot;
        this.limits = limits;
        this.requestLock = requestLock;
        this.states = states;
        this.lineageNodes = lineageNodes;
        this.readCursors = readCursors;
        this.readCursorTokens = readCursorTokens;
        this.stateCursors = stateCursors;
        this.stateCursorTokens = stateCursorTokens;
        this.handleSequence = handleSequence;
        this.creationSequence = creationSequence;
        this.internalSequence = internalSequence;
    }

    public SessionRecord stage() {
        return new SessionRecord(
                sessionId,
                protocolVersion,
                snapshot,
                limits,
                requestLock,
                new LinkedHashMap<>(states),
                new HashMap<>(lineageNodes),
                new HashMap<>(readCursors),
                new HashMap<>(readCursorTokens),
                new HashMap<>(stateCursors),
                new HashMap<>(stateCursorTokens),
                handleSequence,
                creationSequence,
                internalSequence);
    }

    public void publish(SessionRecord staged) {
        if (staged.requestLock != requestLock || !staged.sessionId.equals(sessionId)) {
            throw new IllegalArgumentException("staged data belongs to another session");
        }
        states = staged.states;
        lineageNodes = staged.lineageNodes;
        readCursors = staged.readCursors;
        readCursorTokens = staged.readCursorTokens;
        stateCursors = staged.stateCursors;
        stateCursorTokens = staged.stateCursorTokens;
        handleSequence = staged.handleSequence;
        creationSequence = staged.creationSequence;
        internalSequence = staged.internalSequence;
    }

    public String sessionId() { return sessionId; }
    public String protocolVersion() { return protocolVersion; }
    public Snapshot snapshot() { return snapshot; }
    public ServiceLimits limits() { return limits; }
    public ReentrantLock requestLock() { return requestLock; }
    public Map<String, StateRecord> states() { return states; }
    public Map<String, CanonicalLineageNode> lineageNodes() { return lineageNodes; }
    public Map<String, ReadCursor> readCursors() { return readCursors; }
    public Map<String, String> readCursorTokens() { return readCursorTokens; }
    public Map<String, StateCursor> stateCursors() { return stateCursors; }
    public Map<Long, String> stateCursorTokens() { return stateCursorTokens; }
    public long handleSequence() { return handleSequence; }
    public long creationSequence() { return creationSequence; }
    public long internalSequence() { return internalSequence; }
    public void handleSequence(long value) { handleSequence = value; }
    public void creationSequence(long value) { creationSequence = value; }
    public void internalSequence(long value) { internalSequence = value; }
}
