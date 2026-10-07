package org.indexact.expression;

import java.util.ArrayList;
import java.util.List;

final class ExpressionLists {
    private ExpressionLists() {}

    @SafeVarargs
    static <T> List<T> of(T first, T second, T... rest) {
        ArrayList<T> result = new ArrayList<>(2 + rest.length);
        result.add(first);
        result.add(second);
        result.addAll(List.of(rest));
        return result;
    }
}
