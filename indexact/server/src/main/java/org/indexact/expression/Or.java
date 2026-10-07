package org.indexact.expression;

import java.util.List;

/** Document-level Boolean disjunction; positional choice is represented by ANY_OF. */
public record Or(List<TextCondition> children) implements TextCondition {
    public Or {
        children = ExpressionValidation.children(children, "OR");
    }

    public Or(TextCondition first, TextCondition second, TextCondition... rest) {
        this(ExpressionLists.of(first, second, rest));
    }
}
