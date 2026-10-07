package org.indexact.state;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One immutable content-addressed lineage DAG node. */
public final class CanonicalLineageNode {
    private static final Set<String> CREATORS = Set.of(
            "CORPUS", "FILTER", "INTERSECT", "UNION", "DIFFERENCE",
            "RANK", "TOPK", "RESTRICT", "AS_SET");

    private final String id;
    private final String op;
    private final List<String> parents;
    private final Object args;
    private final byte[] payloadBytes;
    private final byte[] nodeBytes;

    public CanonicalLineageNode(String op, List<String> parents, Object args) {
        if (!CREATORS.contains(op)) {
            throw new IllegalArgumentException("unknown StateCreator: " + op);
        }
        if (parents == null) {
            throw new NullPointerException("parents");
        }
        List<String> parentCopy = List.copyOf(parents);
        for (String parent : parentCopy) {
            if (parent == null || !parent.matches("lin-[0-9a-f]{64}")) {
                throw new IllegalArgumentException("lineage parents must be LineageId values");
            }
        }
        int expectedParents = switch (op) {
            case "CORPUS" -> 0;
            case "FILTER", "RANK", "TOPK", "AS_SET" -> 1;
            case "INTERSECT", "UNION", "DIFFERENCE", "RESTRICT" -> 2;
            default -> throw new AssertionError(op);
        };
        if (parentCopy.size() != expectedParents) {
            throw new IllegalArgumentException(
                    op + " lineage requires " + expectedParents + " parent(s)");
        }
        this.op = op;
        this.parents = parentCopy;
        this.args = freeze(args);

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", op);
        payload.put("parents", this.parents);
        payload.put("args", this.args);
        this.payloadBytes = Jcs.encode(payload);
        this.id = "lin-" + HexFormat.of().formatHex(sha256(payloadBytes));
        this.nodeBytes = Jcs.encode(toWire());
    }

    public String id() {
        return id;
    }

    public String op() {
        return op;
    }

    public List<String> parents() {
        return parents;
    }

    public Object args() {
        return thaw(args);
    }

    public byte[] payloadBytes() {
        return payloadBytes.clone();
    }

    public byte[] nodeBytes() {
        return nodeBytes.clone();
    }

    public int lineageNodeBytes() {
        return nodeBytes.length;
    }

    public Map<String, Object> toWire() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("op", op);
        result.put("parents", parents);
        result.put("args", args);
        return result;
    }

    private static Object freeze(Object value) {
        record Frame(Object value, boolean expanded) {}
        java.util.ArrayDeque<Frame> work = new java.util.ArrayDeque<>();
        java.util.IdentityHashMap<Object, Boolean> active = new java.util.IdentityHashMap<>();
        ArrayList<Object> built = new ArrayList<>();
        work.push(new Frame(value, false));
        while (!work.isEmpty()) {
            Frame frame = work.pop();
            Object current = frame.value();
            if (!frame.expanded() && (current instanceof List<?> || current instanceof Map<?, ?>)) {
                if (active.put(current, Boolean.TRUE) != null) {
                    throw new IllegalArgumentException("lineage args must not contain a cycle");
                }
                work.push(new Frame(current, true));
                List<?> children = current instanceof List<?> list
                        ? list : new ArrayList<>(((Map<?, ?>) current).values());
                for (int index = children.size() - 1; index >= 0; index--) {
                    work.push(new Frame(children.get(index), false));
                }
                continue;
            }
            if (frame.expanded()) {
                active.remove(current);
            }
            if (current instanceof List<?> list) {
                int start = built.size() - list.size();
                List<Object> children = new ArrayList<>(built.subList(start, built.size()));
                built.subList(start, built.size()).clear();
                built.add(Collections.unmodifiableList(children));
            } else if (current instanceof Map<?, ?> map) {
                int start = built.size() - map.size();
                List<Object> children = new ArrayList<>(built.subList(start, built.size()));
                built.subList(start, built.size()).clear();
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                int index = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("lineage object keys must be strings");
                    }
                    if (result.containsKey(key)) {
                        throw new IllegalArgumentException("duplicate lineage object property");
                    }
                    result.put(key, children.get(index++));
                }
                built.add(Collections.unmodifiableMap(result));
            } else if (current == null || current instanceof Boolean || current instanceof String
                    || current instanceof Byte || current instanceof Short
                    || current instanceof Integer || current instanceof Long
                    || current instanceof Float || current instanceof Double) {
                Jcs.encode(current);
                boolean zero = current instanceof Double doubleValue && doubleValue == 0.0
                        || current instanceof Float floatValue && floatValue == 0.0f;
                if (zero) {
                    built.add(0.0);
                } else {
                    built.add(current);
                }
            } else {
                throw new IllegalArgumentException("args is outside the lineage JSON domain");
            }
        }
        return built.getFirst();
    }

    private static Object thaw(Object value) {
        record Frame(Object value, boolean expanded) {}
        java.util.ArrayDeque<Frame> work = new java.util.ArrayDeque<>();
        ArrayList<Object> built = new ArrayList<>();
        work.push(new Frame(value, false));
        while (!work.isEmpty()) {
            Frame frame = work.pop();
            Object current = frame.value();
            if (!frame.expanded() && (current instanceof List<?> || current instanceof Map<?, ?>)) {
                work.push(new Frame(current, true));
                List<?> children = current instanceof List<?> list
                        ? list : new ArrayList<>(((Map<?, ?>) current).values());
                for (int index = children.size() - 1; index >= 0; index--) {
                    work.push(new Frame(children.get(index), false));
                }
                continue;
            }
            if (current instanceof List<?> list) {
                int start = built.size() - list.size();
                List<Object> children = new ArrayList<>(built.subList(start, built.size()));
                built.subList(start, built.size()).clear();
                built.add(children);
            } else if (current instanceof Map<?, ?> map) {
                int start = built.size() - map.size();
                List<Object> children = new ArrayList<>(built.subList(start, built.size()));
                built.subList(start, built.size()).clear();
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                int index = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    result.put((String) entry.getKey(), children.get(index++));
                }
                built.add(result);
            } else {
                built.add(current);
            }
        }
        return built.getFirst();
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError("SHA-256 is required by Java", error);
        }
    }
}
