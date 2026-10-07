package org.indexact.expression;

import java.util.List;

/** Document-level Boolean conjunction. */
public record And(List<TextCondition> children) implements TextCondition {
    public And {
        children = ExpressionValidation.children(children, "AND");
    }

    public And(TextCondition first, TextCondition second, TextCondition... rest) {
        this(ExpressionLists.of(first, second, rest));
    }
}
