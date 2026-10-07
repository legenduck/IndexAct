package org.indexact.expression;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Recursive, duplicate-preserving GAP_CHOICE_EXPAND utilities. */
public final class GapChoice {
    private GapChoice() {}

    public static BigInteger expansionCount(List<LexicalExpression> children) {
        requireChildren(children);
        BigInteger result = BigInteger.ONE;
        IdentityHashMap<LexicalExpression, BigInteger> memo = new IdentityHashMap<>();
        for (LexicalExpression child : children) {
            computeExpandedCounts(child, memo);
            result = result.multiply(memo.get(child));
        }
        return result;
    }

    public static void forEachExpandedTuple(
            List<LexicalExpression> children, Consumer<List<LexicalExpression>> consumer) {
        requireChildren(children);
        Objects.requireNonNull(consumer, "consumer");
        java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();
        scheduleChildren(children, 0, List.of(), consumer, tasks);
        while (!tasks.isEmpty()) {
            tasks.pop().run();
        }
    }

    private static void requireChildren(List<LexicalExpression> children) {
        Objects.requireNonNull(children, "children");
        if (children.size() < 2) {
            throw new IllegalArgumentException("gap-sensitive expansion requires two children");
        }
        children.forEach(child -> Objects.requireNonNull(child, "gap-sensitive child"));
    }

    private record CountFrame(LexicalExpression expression, boolean expanded) {}

    private static void computeExpandedCounts(
            LexicalExpression root,
            IdentityHashMap<LexicalExpression, BigInteger> memo) {
        java.util.ArrayDeque<CountFrame> pending = new java.util.ArrayDeque<>();
        pending.push(new CountFrame(root, false));
        while (!pending.isEmpty()) {
            CountFrame frame = pending.pop();
            LexicalExpression current = frame.expression();
            if (memo.containsKey(current)) {
                continue;
            }
            List<LexicalExpression> currentChildren = lexicalChildren(current);
            if (!frame.expanded()) {
                pending.push(new CountFrame(current, true));
                for (int index = currentChildren.size() - 1; index >= 0; index--) {
                    pending.push(new CountFrame(currentChildren.get(index), false));
                }
                continue;
            }
            BigInteger count;
            if (current instanceof Term) {
                count = BigInteger.ONE;
            } else if (current instanceof AnyOf) {
                count = BigInteger.ZERO;
                for (LexicalExpression child : currentChildren) {
                    count = count.add(memo.get(child));
                }
            } else {
                count = BigInteger.ONE;
                for (LexicalExpression child : currentChildren) {
                    count = count.multiply(memo.get(child));
                }
            }
            memo.put(current, count);
        }
    }

    private static List<LexicalExpression> lexicalChildren(LexicalExpression expression) {
        return switch (expression) {
            case Term ignored -> List.of();
            case AnyOf any -> any.children();
            case Phrase phrase -> phrase.children();
            case Near near -> near.children();
        };
    }

    private static void scheduleExpression(
            LexicalExpression expression,
            Consumer<LexicalExpression> consumer,
            java.util.ArrayDeque<Runnable> tasks) {
        tasks.push(() -> {
            switch (expression) {
                case Term term -> tasks.push(() -> consumer.accept(term));
                case AnyOf any -> {
                    List<LexicalExpression> children = any.children();
                    for (int index = children.size() - 1; index >= 0; index--) {
                        scheduleExpression(children.get(index), consumer, tasks);
                    }
                }
                case Phrase phrase -> scheduleChildren(
                        phrase.children(), 0, List.of(),
                        children -> tasks.push(
                                () -> consumer.accept(new Phrase(children))), tasks);
                case Near near -> scheduleChildren(
                        near.children(), 0, List.of(),
                        children -> tasks.push(() -> consumer.accept(new Near(
                                children, near.ordered(), near.maxGaps()))), tasks);
            }
        });
    }

    private static void scheduleChildren(
            List<LexicalExpression> source,
            int index,
            List<LexicalExpression> selected,
            Consumer<List<LexicalExpression>> consumer,
            java.util.ArrayDeque<Runnable> tasks) {
        tasks.push(() -> {
            if (index == source.size()) {
                tasks.push(() -> consumer.accept(selected));
                return;
            }
            scheduleExpression(source.get(index), expanded -> {
                ArrayList<LexicalExpression> next = new ArrayList<>(selected.size() + 1);
                next.addAll(selected);
                next.add(expanded);
                scheduleChildren(source, index + 1, List.copyOf(next), consumer, tasks);
            }, tasks);
        });
    }
}
