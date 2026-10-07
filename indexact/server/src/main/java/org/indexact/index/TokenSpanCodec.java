package org.indexact.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.util.BytesRef;
import org.indexact.index.Snapshot.TokenSpan;
import org.indexact.analysis.ContractAnalyzer.Token;

/** Deterministic binary encoding of dense per-position, non-text token-span metadata. */
final class TokenSpanCodec {
    private static final int VALUES_PER_TOKEN = 4;

    private TokenSpanCodec() {}

    /** Same wire bytes without allocating a second per-token object graph during indexing. */
    static byte[] encodeTokens(List<Token> tokens, int[] rawBoundaries) {
        int bytes = Math.addExact(Integer.BYTES, Math.multiplyExact(tokens.size(), 16));
        ByteBuffer output = ByteBuffer.allocate(bytes);
        output.putInt(tokens.size());
        for (Token token : tokens) {
            output.putInt(token.rawStart());
            output.putInt(token.rawEnd());
            output.putInt(rawBoundaries[token.rawStart()]);
            output.putInt(rawBoundaries[token.rawEnd()]);
        }
        return output.array();
    }

    static byte[] encode(List<TokenSpan> spans) {
        int bytes = Math.addExact(Integer.BYTES, Math.multiplyExact(spans.size(), 16));
        ByteBuffer output = ByteBuffer.allocate(bytes);
        output.putInt(spans.size());
        for (TokenSpan span : spans) {
            output.putInt(span.rawStart());
            output.putInt(span.rawEnd());
            output.putInt(span.utf16Start());
            output.putInt(span.utf16End());
        }
        return output.array();
    }

    static List<TokenSpan> decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length < Integer.BYTES) {
            throw new IOException("missing or truncated token-span metadata");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded);
        int count = input.getInt();
        if (count < 0
                || input.remaining() != (long) count * VALUES_PER_TOKEN * Integer.BYTES) {
            throw new IOException("invalid token-span metadata length");
        }
        List<TokenSpan> spans = new ArrayList<>(count);
        try {
            for (int index = 0; index < count; index++) {
                spans.add(new TokenSpan(
                        input.getInt(), input.getInt(), input.getInt(), input.getInt()));
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid token-span metadata value", error);
        }
        return List.copyOf(spans);
    }

    /** Full startup validation without allocating a Java object for each token. */
    static void validate(BytesRef encoded, long tokenLength, long rawLength) throws IOException {
        if (encoded == null || encoded.length < Integer.BYTES) {
            throw new IOException("missing or truncated token-span metadata");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded.bytes, encoded.offset, encoded.length);
        int count = input.getInt();
        if (count < 0 || input.remaining() != (long) count * VALUES_PER_TOKEN * Integer.BYTES) {
            throw new IOException("invalid token-span metadata length");
        }
        if (count != tokenLength) {
            throw new IOException("token-span count disagrees with len_tokens");
        }
        for (int index = 0; index < count; index++) {
            int rawStart = input.getInt();
            int rawEnd = input.getInt();
            int utf16Start = input.getInt();
            int utf16End = input.getInt();
            if (rawStart < 0 || rawEnd <= rawStart || utf16Start < 0 || utf16End <= utf16Start) {
                throw new IOException("invalid token-span metadata value");
            }
            if (rawEnd > rawLength || utf16Start < rawStart || utf16End < rawEnd) {
                throw new IOException("token span disagrees with immutable raw coordinates");
            }
        }
    }
}
