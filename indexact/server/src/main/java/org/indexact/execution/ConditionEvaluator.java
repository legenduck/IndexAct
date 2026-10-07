package org.indexact.execution;

import java.io.IOException;
import java.util.Objects;
import org.indexact.expression.And;
import org.indexact.expression.LexicalExpression;
import org.indexact.expression.Not;
import org.indexact.expression.Or;
import org.indexact.expression.TextCondition;

/** Direct document-level SAT semantics bound to one occurrence evaluator. */
public final class ConditionEvaluator {
    private final OccurrenceEvaluator occurrences;

    public ConditionEvaluator(OccurrenceEvaluator occurrences) {
        this.occurrences = Objects.requireNonNull(occurrences, "occurrences");
    }

    public boolean satisfies(TextCondition condition) throws IOException {
        Objects.requireNonNull(condition, "condition");
        record Frame(int action, TextCondition condition, int index) {}
        java.util.ArrayDeque<Frame> tasks = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Boolean> values = new java.util.ArrayDeque<>();
        tasks.push(new Frame(0, condition, 0));
        while (!tasks.isEmpty()) {
            Frame frame = tasks.pop();
            TextCondition current = frame.condition();
            if (frame.action() == 0) {
                switch (current) {
                    case LexicalExpression lexical ->
                            values.push(!occurrences.occurrences(lexical).isEmpty());
                    case Not not -> {
                        tasks.push(new Frame(1, current, 0));
                        tasks.push(new Frame(0, not.child(), 0));
                    }
                    case And and -> {
                        tasks.push(new Frame(2, current, 1));
                        tasks.push(new Frame(0, and.children().getFirst(), 0));
                    }
                    case Or or -> {
                        tasks.push(new Frame(3, current, 1));
                        tasks.push(new Frame(0, or.children().getFirst(), 0));
                    }
                }
            } else if (frame.action() == 1) {
                values.push(!values.pop());
            } else if (frame.action() == 2) {
                And and = (And) current;
                if (!values.pop()) {
                    values.push(false);
                } else if (frame.index() == and.children().size()) {
                    values.push(true);
                } else {
                    tasks.push(new Frame(2, current, frame.index() + 1));
                    tasks.push(new Frame(0, and.children().get(frame.index()), 0));
                }
            } else {
                Or or = (Or) current;
                if (values.pop()) {
                    values.push(true);
                } else if (frame.index() == or.children().size()) {
                    values.push(false);
                } else {
                    tasks.push(new Frame(3, current, frame.index() + 1));
                    tasks.push(new Frame(0, or.children().get(frame.index()), 0));
                }
            }
        }
        if (values.size() != 1) {
            throw new IllegalStateException("condition evaluation did not produce one result");
        }
        return values.pop();
    }
}
