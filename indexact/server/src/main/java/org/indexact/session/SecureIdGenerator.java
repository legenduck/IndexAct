package org.indexact.session;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Process-local cryptographically strong default identifier generator. */
public final class SecureIdGenerator implements IdGenerator {
    private final SecureRandom random;

    public SecureIdGenerator() {
        this(new SecureRandom());
    }

    public SecureIdGenerator(SecureRandom random) {
        this.random = java.util.Objects.requireNonNull(random, "random");
    }

    @Override
    public synchronized String hex(int byteCount) {
        if (byteCount < 1) {
            throw new IllegalArgumentException("byteCount must be positive");
        }
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
