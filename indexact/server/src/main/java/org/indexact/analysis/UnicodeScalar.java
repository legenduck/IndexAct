package org.indexact.analysis;

import java.util.ArrayList;
import java.util.List;

/** Unicode-scalar utilities that do not consult the JDK's versioned Unicode properties. */
public final class UnicodeScalar {
    public static final int MAX_CODE_POINT = 0x10FFFF;
    public static final int SURROGATE_START = 0xD800;
    public static final int SURROGATE_END = 0xDFFF;

    private UnicodeScalar() {}

    /** Rejects unpaired UTF-16 surrogates and returns the scalar sequence. */
    public static int[] toCodePoints(String text) {
        if (text == null) {
            throw new NullPointerException("text");
        }
        List<Integer> result = new ArrayList<>(text.length());
        for (int utf16 = 0; utf16 < text.length(); ) {
            char first = text.charAt(utf16);
            if (Character.isHighSurrogate(first)) {
                if (utf16 + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(utf16 + 1))) {
                    throw invalidScalar(utf16);
                }
                result.add(Character.toCodePoint(first, text.charAt(utf16 + 1)));
                utf16 += 2;
            } else if (Character.isLowSurrogate(first)) {
                throw invalidScalar(utf16);
            } else {
                result.add((int) first);
                utf16++;
            }
        }
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Maps every raw code-point boundary to its UTF-16 boundary. */
    public static int[] codePointToUtf16Boundaries(String text) {
        int[] codePoints = toCodePoints(text);
        int[] boundaries = new int[codePoints.length + 1];
        int utf16 = 0;
        for (int index = 0; index < codePoints.length; index++) {
            boundaries[index] = utf16;
            utf16 += codePoints[index] >= 0x10000 ? 2 : 1;
        }
        boundaries[codePoints.length] = utf16;
        return boundaries;
    }

    public static String fromCodePoints(int[] codePoints) {
        StringBuilder result = new StringBuilder(codePoints.length);
        for (int codePoint : codePoints) {
            requireScalar(codePoint);
            result.appendCodePoint(codePoint);
        }
        return result.toString();
    }

    public static void requireScalar(int codePoint) {
        if (codePoint < 0
                || codePoint > MAX_CODE_POINT
                || codePoint >= SURROGATE_START && codePoint <= SURROGATE_END) {
            throw new IllegalArgumentException(
                    "not a Unicode scalar: U+" + Integer.toHexString(codePoint).toUpperCase());
        }
    }

    private static IllegalArgumentException invalidScalar(int utf16Index) {
        return new IllegalArgumentException(
                "text contains an unpaired surrogate at UTF-16 index " + utf16Index);
    }
}
