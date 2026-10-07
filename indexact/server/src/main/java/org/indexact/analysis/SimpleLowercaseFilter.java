package org.indexact.analysis;

import org.indexact.analysis.generated.Unicode17Tables;

/** Unicode 17.0.0 simple, locale-independent lowercase mapping. */
public final class SimpleLowercaseFilter {
    public String apply(int[] codePoints, int start, int end) {
        if (start < 0 || end < start || end > codePoints.length) {
            throw new IndexOutOfBoundsException("invalid lowercase bounds");
        }
        StringBuilder result = new StringBuilder(end - start);
        for (int index = start; index < end; index++) {
            result.appendCodePoint(Unicode17Tables.simpleLowercase(codePoints[index]));
        }
        return result.toString();
    }
}
