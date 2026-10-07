package org.indexact.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.indexact.analysis.generated.Unicode17Tables;

/** UAX #29 Revision 47 default word boundaries and contract segment emission. */
public final class Uax29Tokenizer {
    private static final Set<String> IGNORE = Set.of("Extend", "Format", "ZWJ");
    private static final Set<String> NEWLINE = Set.of("Newline", "CR", "LF");
    private static final Set<String> AH_LETTER = Set.of("ALetter", "Hebrew_Letter");
    private static final Set<String> MID_LETTER =
            Set.of("MidLetter", "MidNumLet", "Single_Quote");
    private static final Set<String> MID_NUMBER =
            Set.of("MidNum", "MidNumLet", "Single_Quote");
    private static final Set<String> EXTEND_NUM_LEFT =
            Set.of("ALetter", "Hebrew_Letter", "Numeric", "Katakana", "ExtendNumLet");
    private static final Set<String> EXTEND_NUM_RIGHT =
            Set.of("ALetter", "Hebrew_Letter", "Numeric", "Katakana");

    public record Segment(int start, int end) {
        public Segment {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("segment must be a non-empty span");
            }
        }
    }

    public List<Integer> wordBoundaries(String text) {
        int[] codePoints = UnicodeScalar.toCodePoints(text);
        if (codePoints.length == 0) {
            return List.of(0);
        }
        String[] properties = new String[codePoints.length];
        for (int index = 0; index < codePoints.length; index++) {
            properties[index] = Unicode17Tables.wordBreak(codePoints[index]);
        }

        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        for (int boundary = 1; boundary < codePoints.length; boundary++) {
            if (shouldBreak(codePoints, properties, boundary)) {
                boundaries.add(boundary);
            }
        }
        boundaries.add(codePoints.length);
        return List.copyOf(boundaries);
    }

    /** Returns all UAX segments; the analyzer applies {@link #emits(int[], int, int)}. */
    public List<Segment> segments(String text) {
        List<Integer> boundaries = wordBoundaries(text);
        List<Segment> segments = new ArrayList<>(Math.max(0, boundaries.size() - 1));
        for (int index = 0; index + 1 < boundaries.size(); index++) {
            segments.add(new Segment(boundaries.get(index), boundaries.get(index + 1)));
        }
        return List.copyOf(segments);
    }

    public boolean emits(int[] codePoints, int start, int end) {
        if (start < 0 || end < start || end > codePoints.length) {
            throw new IndexOutOfBoundsException("invalid segment bounds");
        }
        for (int index = start; index < end; index++) {
            int codePoint = codePoints[index];
            String category = Unicode17Tables.generalCategory(codePoint);
            if (category.startsWith("L")
                    || category.startsWith("N")
                    || Unicode17Tables.isExtendedPictographic(codePoint)
                    || Unicode17Tables.wordBreak(codePoint).equals("Regional_Indicator")) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldBreak(int[] codePoints, String[] properties, int boundary) {
        int leftDirect = boundary - 1;
        int right = boundary;
        String leftDirectProperty = properties[leftDirect];
        String rightProperty = properties[right];

        // WB3, WB3a, WB3b, WB3c, WB3d precede WB4.
        if (leftDirectProperty.equals("CR") && rightProperty.equals("LF")) {
            return false;
        }
        if (NEWLINE.contains(leftDirectProperty) || NEWLINE.contains(rightProperty)) {
            return true;
        }
        if (leftDirectProperty.equals("ZWJ")
                && Unicode17Tables.isExtendedPictographic(codePoints[right])) {
            return false;
        }
        if (leftDirectProperty.equals("WSegSpace") && rightProperty.equals("WSegSpace")) {
            return false;
        }

        // WB4: Extend, Format, and ZWJ inherit the preceding significant property.
        if (IGNORE.contains(rightProperty)) {
            return false;
        }
        int left = previousSignificant(properties, leftDirect);
        if (left < 0) {
            return true;
        }
        String leftProperty = properties[left];
        int previous = previousSignificant(properties, left - 1);
        int next = nextSignificant(properties, right + 1);
        String previousProperty = previous < 0 ? null : properties[previous];
        String nextProperty = next < 0 ? null : properties[next];

        // WB5-WB7c.
        if (AH_LETTER.contains(leftProperty) && AH_LETTER.contains(rightProperty)) {
            return false;
        }
        if (AH_LETTER.contains(leftProperty)
                && MID_LETTER.contains(rightProperty)
                && nextProperty != null
                && AH_LETTER.contains(nextProperty)) {
            return false;
        }
        if (previousProperty != null
                && AH_LETTER.contains(previousProperty)
                && MID_LETTER.contains(leftProperty)
                && AH_LETTER.contains(rightProperty)) {
            return false;
        }
        if (leftProperty.equals("Hebrew_Letter") && rightProperty.equals("Single_Quote")) {
            return false;
        }
        if (leftProperty.equals("Hebrew_Letter")
                && rightProperty.equals("Double_Quote")
                && "Hebrew_Letter".equals(nextProperty)) {
            return false;
        }
        if ("Hebrew_Letter".equals(previousProperty)
                && leftProperty.equals("Double_Quote")
                && rightProperty.equals("Hebrew_Letter")) {
            return false;
        }

        // WB8-WB12.
        if (leftProperty.equals("Numeric") && rightProperty.equals("Numeric")) {
            return false;
        }
        if (AH_LETTER.contains(leftProperty) && rightProperty.equals("Numeric")) {
            return false;
        }
        if (leftProperty.equals("Numeric") && AH_LETTER.contains(rightProperty)) {
            return false;
        }
        if ("Numeric".equals(previousProperty)
                && MID_NUMBER.contains(leftProperty)
                && rightProperty.equals("Numeric")) {
            return false;
        }
        if (leftProperty.equals("Numeric")
                && MID_NUMBER.contains(rightProperty)
                && "Numeric".equals(nextProperty)) {
            return false;
        }

        // WB13-WB13b.
        if (leftProperty.equals("Katakana") && rightProperty.equals("Katakana")) {
            return false;
        }
        if (EXTEND_NUM_LEFT.contains(leftProperty) && rightProperty.equals("ExtendNumLet")) {
            return false;
        }
        if (leftProperty.equals("ExtendNumLet") && EXTEND_NUM_RIGHT.contains(rightProperty)) {
            return false;
        }

        // WB15/WB16.
        if (leftProperty.equals("Regional_Indicator")
                && rightProperty.equals("Regional_Indicator")) {
            int regionalIndicators = 0;
            int scan = left;
            while (scan >= 0 && properties[scan].equals("Regional_Indicator")) {
                regionalIndicators++;
                scan = previousSignificant(properties, scan - 1);
            }
            if ((regionalIndicators & 1) == 1) {
                return false;
            }
        }

        return true; // WB999.
    }

    private static int previousSignificant(String[] properties, int start) {
        int index = start;
        while (index >= 0 && IGNORE.contains(properties[index])) {
            index--;
        }
        return index;
    }

    private static int nextSignificant(String[] properties, int start) {
        int index = start;
        while (index < properties.length && IGNORE.contains(properties[index])) {
            index++;
        }
        return index < properties.length ? index : -1;
    }
}
