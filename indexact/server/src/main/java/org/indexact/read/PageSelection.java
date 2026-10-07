package org.indexact.read;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.indexact.analysis.UnicodeScalar;

/** PAGE metadata resolved by the session/cursor layer before region evaluation. */
public record PageSelection(List<String> docKeys, String nextCursor)
        implements StateDocumentSelection {
    public PageSelection {
        docKeys = List.copyOf(Objects.requireNonNull(docKeys, "docKeys"));
        HashSet<String> unique = new HashSet<>();
        for (String docKey : docKeys) {
            UnicodeScalar.toCodePoints(Objects.requireNonNull(docKey, "docKey"));
            if (!unique.add(docKey)) {
                throw new IllegalArgumentException("page DocKeys must not contain duplicates");
            }
        }
        if (nextCursor != null && nextCursor.isEmpty()) {
            throw new IllegalArgumentException("an opaque next cursor must not be empty");
        }
    }

    public PageSelection(List<String> docKeys) {
        this(docKeys, null);
    }
}
