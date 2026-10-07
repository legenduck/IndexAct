package org.indexact.read;

import java.io.IOException;
import org.indexact.index.Snapshot;

/** Instrumentable stored-raw boundary used only after semantic budget admission. */
@FunctionalInterface
public interface RawTextAccessor {
    String load(Snapshot snapshot, String docKey) throws IOException;
}
