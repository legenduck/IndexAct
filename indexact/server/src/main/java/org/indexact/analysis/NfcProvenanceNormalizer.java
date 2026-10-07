package org.indexact.analysis;

import java.util.ArrayList;
import java.util.List;
import org.indexact.analysis.generated.Unicode17Tables;

/** UAX #15 Revision 57 NFC with raw-code-point provenance. */
public final class NfcProvenanceNormalizer {
    private static final int S_BASE = 0xAC00;
    private static final int L_BASE = 0x1100;
    private static final int V_BASE = 0x1161;
    private static final int T_BASE = 0x11A7;
    private static final int L_COUNT = 19;
    private static final int V_COUNT = 21;
    private static final int T_COUNT = 28;
    private static final int N_COUNT = V_COUNT * T_COUNT;
    private static final int S_COUNT = L_COUNT * N_COUNT;

    public record Provenance(int rawStart, int rawEnd) {
        public Provenance {
            if (rawStart < 0 || rawEnd <= rawStart) {
                throw new IllegalArgumentException("provenance must be a non-empty non-negative span");
            }
        }
    }

    public record NormalizedText(String text, List<Provenance> provenance) {
        public NormalizedText {
            provenance = List.copyOf(provenance);
            if (UnicodeScalar.toCodePoints(text).length != provenance.size()) {
                throw new IllegalArgumentException("one provenance span is required per NFC scalar");
            }
        }
    }

    private record Scalar(int codePoint, int rawStart, int rawEnd) {
        Scalar {
            UnicodeScalar.requireScalar(codePoint);
        }
    }

    public NormalizedText normalize(String rawText) {
        int[] raw = UnicodeScalar.toCodePoints(rawText);
        List<Scalar> decomposed = new ArrayList<>(raw.length);
        for (int rawIndex = 0; rawIndex < raw.length; rawIndex++) {
            decompose(raw[rawIndex], rawIndex, rawIndex + 1, decomposed);
        }

        List<Scalar> reordered = canonicalReorder(decomposed);
        List<Scalar> composed = canonicalCompose(reordered);
        StringBuilder text = new StringBuilder(composed.size());
        List<Provenance> provenance = new ArrayList<>(composed.size());
        for (Scalar scalar : composed) {
            text.appendCodePoint(scalar.codePoint());
            provenance.add(new Provenance(scalar.rawStart(), scalar.rawEnd()));
        }
        return new NormalizedText(text.toString(), provenance);
    }

    private static void decompose(
            int codePoint, int rawStart, int rawEnd, List<Scalar> output) {
        int[] mapping = hangulDecomposition(codePoint);
        if (mapping == null) {
            mapping = Unicode17Tables.canonicalDecomposition(codePoint);
        }
        if (mapping == null) {
            output.add(new Scalar(codePoint, rawStart, rawEnd));
            return;
        }
        for (int child : mapping) {
            decompose(child, rawStart, rawEnd, output);
        }
    }

    private static int[] hangulDecomposition(int codePoint) {
        int sIndex = codePoint - S_BASE;
        if (sIndex < 0 || sIndex >= S_COUNT) {
            return null;
        }
        int leading = L_BASE + sIndex / N_COUNT;
        int vowel = V_BASE + sIndex % N_COUNT / T_COUNT;
        int trailingIndex = sIndex % T_COUNT;
        return trailingIndex == 0
                ? new int[] {leading, vowel}
                : new int[] {leading, vowel, T_BASE + trailingIndex};
    }

    private static List<Scalar> canonicalReorder(List<Scalar> scalars) {
        List<Scalar> ordered = new ArrayList<>(scalars.size());
        for (Scalar scalar : scalars) {
            int combiningClass = Unicode17Tables.combiningClass(scalar.codePoint());
            int insertion = ordered.size();
            if (combiningClass != 0) {
                while (insertion > 0) {
                    int previousClass = Unicode17Tables.combiningClass(
                            ordered.get(insertion - 1).codePoint());
                    if (previousClass == 0 || previousClass <= combiningClass) {
                        break;
                    }
                    insertion--;
                }
            }
            ordered.add(insertion, scalar);
        }
        return ordered;
    }

    private static List<Scalar> canonicalCompose(List<Scalar> scalars) {
        if (scalars.isEmpty()) {
            return List.of();
        }

        List<Scalar> result = new ArrayList<>(scalars.size());
        result.add(scalars.getFirst());
        int starterPosition = 0;
        int starter = scalars.getFirst().codePoint();
        int lastClass = 0;

        for (int index = 1; index < scalars.size(); index++) {
            Scalar scalar = scalars.get(index);
            int combiningClass = Unicode17Tables.combiningClass(scalar.codePoint());
            int composite = composition(starter, scalar.codePoint());
            if (composite >= 0 && (lastClass < combiningClass || lastClass == 0)) {
                Scalar prior = result.get(starterPosition);
                result.set(starterPosition, new Scalar(
                        composite,
                        Math.min(prior.rawStart(), scalar.rawStart()),
                        Math.max(prior.rawEnd(), scalar.rawEnd())));
                starter = composite;
                continue;
            }

            if (combiningClass == 0) {
                starterPosition = result.size();
                starter = scalar.codePoint();
            }
            lastClass = combiningClass;
            result.add(scalar);
        }
        return result;
    }

    private static int composition(int first, int second) {
        int leadingIndex = first - L_BASE;
        if (leadingIndex >= 0 && leadingIndex < L_COUNT) {
            int vowelIndex = second - V_BASE;
            if (vowelIndex >= 0 && vowelIndex < V_COUNT) {
                return S_BASE + (leadingIndex * V_COUNT + vowelIndex) * T_COUNT;
            }
        }

        int syllableIndex = first - S_BASE;
        if (syllableIndex >= 0 && syllableIndex < S_COUNT && syllableIndex % T_COUNT == 0) {
            int trailingIndex = second - T_BASE;
            if (trailingIndex > 0 && trailingIndex < T_COUNT) {
                return first + trailingIndex;
            }
        }
        return Unicode17Tables.compose(first, second);
    }
}
