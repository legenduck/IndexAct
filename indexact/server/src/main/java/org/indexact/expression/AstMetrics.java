package org.indexact.expression;

import java.math.BigInteger;
import java.util.List;

/** Exact request-static AST accounting over the preserved public syntax. */
public final class AstMetrics {
    public record Metrics(long nodeCount, long depth, BigInteger gapChoiceBranchCost) {
        public Metrics {
            if (nodeCount < 1 || depth < 1 || gapChoiceBranchCost.signum() < 0) {
                throw new IllegalArgumentException("invalid AST metrics");
            }
        }
    }

    private AstMetrics() {}

    public static Metrics measure(Expression expression) {
        if (expression == null) {
            throw new NullPointerException("expression");
        }
        record Frame(Expression expression, long depth) {}
        java.util.ArrayDeque<Frame> pending = new java.util.ArrayDeque<>();
        pending.push(new Frame(expression, 1));
        long nodes = 0;
        long depth = 1;
        BigInteger cost = BigInteger.ZERO;
        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            nodes = Math.addExact(nodes, 1);
            depth = Math.max(depth, frame.depth());
            Expression current = frame.expression();
            if (current instanceof Near near) {
                cost = cost.add(GapChoice.expansionCount(near.children()));
            } else if (current instanceof Phrase phrase) {
                cost = cost.add(GapChoice.expansionCount(phrase.children()));
            }
            List<? extends Expression> currentChildren = children(current);
            for (int index = currentChildren.size() - 1; index >= 0; index--) {
                pending.push(new Frame(
                        currentChildren.get(index), Math.addExact(frame.depth(), 1)));
            }
        }
        return new Metrics(nodes, depth, cost);
    }

    static List<? extends Expression> children(Expression expression) {
        return switch (expression) {
            case Term ignored -> List.of();
            case AnyOf anyOf -> anyOf.children();
            case Phrase phrase -> phrase.children();
            case Near near -> near.children();
            case And and -> and.children();
            case Or or -> or.children();
            case Not not -> List.of(not.child());
            case Combine combine -> combine.terms();
            case Weight weight -> weight.atoms().stream().map(WeightedTerm::term).toList();
        };
    }
}
