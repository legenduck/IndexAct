package org.indexact.session;

/** Injectable lowercase-hex entropy source used for all opaque public identifiers. */
@FunctionalInterface
public interface IdGenerator {
    /** Returns exactly {@code byteCount * 2} lowercase hexadecimal digits. */
    String hex(int byteCount);
}
