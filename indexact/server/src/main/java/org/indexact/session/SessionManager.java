package org.indexact.session;

import java.util.HashMap;
import java.util.Map;

/** Lifecycle registry; callers use each record's request lock for session serialization. */
public final class SessionManager {
    private final Map<String, SessionRecord> sessions = new HashMap<>();

    public synchronized SessionRecord get(String sessionId) {
        return sessions.get(sessionId);
    }

    public synchronized boolean isCurrent(String sessionId, SessionRecord expected) {
        return sessions.get(sessionId) == expected;
    }

    public synchronized boolean publishIfAbsent(SessionRecord session) {
        return sessions.putIfAbsent(session.sessionId(), session) == null;
    }

    public synchronized void removeIfCurrent(String sessionId, SessionRecord expected) {
        sessions.remove(sessionId, expected);
    }

    public synchronized boolean contains(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public synchronized java.util.List<String> activeSessionIds() {
        return sessions.keySet().stream().sorted().toList();
    }
}
