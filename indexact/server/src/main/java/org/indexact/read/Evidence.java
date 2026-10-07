package org.indexact.read;

import java.util.Objects;
import org.indexact.analysis.UnicodeScalar;

/** One exact half-open raw-code-point materialization. */
public record Evidence(String snapshotId, String docKey, long start, long end, String text) {
    public Evidence {
        Objects.requireNonNull(snapshotId, "snapshotId");
        UnicodeScalar.toCodePoints(Objects.requireNonNull(docKey, "docKey"));
        int[] scalars = UnicodeScalar.toCodePoints(Objects.requireNonNull(text, "text"));
        ReadValues.requireNonNegativeSafe(start, "start");
        ReadValues.requireNonNegativeSafe(end, "end");
        if (start > end || end - start != scalars.length) {
            throw new IllegalArgumentException("Evidence coordinates do not match its text");
        }
    }
}
