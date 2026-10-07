package org.indexact.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;

/** Reachability, retained-byte accounting, depth checking, and pruning for lineage DAGs. */
public final class LineageGraph {
    public record Analysis(Set<String> nodeIds, long nodeCount, long totalBytes, long maxDepth) {
        public Analysis {
            nodeIds = Set.copyOf(nodeIds);
        }
    }

    private LineageGraph() {}

    public static Analysis analyze(
            Iterable<String> roots, Map<String, CanonicalLineageNode> nodes) {
        HashSet<String> reachable = new HashSet<>();
        HashMap<String, Long> depths = new HashMap<>();
        long maxDepth = 0;
        for (String root : roots) {
            maxDepth = Math.max(maxDepth, visitIterative(root, nodes, reachable, depths));
        }
        long bytes = 0;
        for (String id : reachable) {
            bytes = Math.addExact(bytes, nodes.get(id).lineageNodeBytes());
        }
        return new Analysis(reachable, reachable.size(), bytes, maxDepth);
    }

    public static void prune(
            Map<String, CanonicalLineageNode> nodes, Iterable<String> activeRoots) {
        Set<String> reachable = analyze(activeRoots, nodes).nodeIds();
        nodes.keySet().removeIf(id -> !reachable.contains(id));
    }

    private static long visitIterative(
            String id,
            Map<String, CanonicalLineageNode> nodes,
            Set<String> reachable,
            Map<String, Long> depths) {
        Long cached = depths.get(id);
        if (cached != null) {
            reachable.add(id);
            return cached;
        }
        final class Frame {
            final String nodeId;
            final CanonicalLineageNode node;
            int parentIndex;
            long depth;

            Frame(String nodeId) {
                this.nodeId = nodeId;
                this.node = nodes.get(nodeId);
                if (node == null) {
                    throw new IllegalStateException("missing retained lineage ancestor " + nodeId);
                }
            }
        }
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        HashSet<String> visiting = new HashSet<>();
        stack.push(new Frame(id));
        visiting.add(id);
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.parentIndex < frame.node.parents().size()) {
                String parentId = frame.node.parents().get(frame.parentIndex++);
                Long parentDepth = depths.get(parentId);
                if (parentDepth != null) {
                    reachable.add(parentId);
                    frame.depth = Math.max(frame.depth, Math.addExact(1, parentDepth));
                    continue;
                }
                if (!visiting.add(parentId)) {
                    throw new IllegalStateException("lineage graph contains a cycle");
                }
                stack.push(new Frame(parentId));
                continue;
            }
            stack.pop();
            visiting.remove(frame.nodeId);
            depths.put(frame.nodeId, frame.depth);
            reachable.add(frame.nodeId);
            if (!stack.isEmpty()) {
                Frame child = stack.peek();
                child.depth = Math.max(child.depth, Math.addExact(1, frame.depth));
            }
        }
        return depths.get(id);
    }
}
