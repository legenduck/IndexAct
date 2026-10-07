package org.indexact.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.indexact.analysis.NfcProvenanceNormalizer.NormalizedText;
import org.indexact.analysis.NfcProvenanceNormalizer.Provenance;
import org.indexact.analysis.Uax29Tokenizer.Segment;
import org.indexact.analysis.generated.Unicode17Tables;

/** Independent implementation of {@code ANALYZER_CONTRACT_v2.2}. */
public final class ContractAnalyzer extends Analyzer {
    public static final String CONTRACT_VERSION = "ANALYZER_CONTRACT_v2.2";
    public static final String UNICODE_VERSION = "17.0.0";
    public static final int UAX15_REVISION = 57;
    public static final int UAX29_REVISION = 47;

    static {
        if (!UNICODE_VERSION.equals(Unicode17Tables.UNICODE_VERSION)) {
            throw new ExceptionInInitializerError(
                    "Unicode table version " + Unicode17Tables.UNICODE_VERSION
                            + " does not match analyzer " + UNICODE_VERSION);
        }
    }

    public record Token(String text, int position, int rawStart, int rawEnd) {
        public Token {
            if (position < 0 || rawStart < 0 || rawEnd <= rawStart) {
                throw new IllegalArgumentException("invalid token coordinates");
            }
        }

        public int utf8Length() {
            return text.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    public record AnalyzedText(String rawText, String analysisNfc, List<Token> tokens) {
        public AnalyzedText {
            UnicodeScalar.toCodePoints(rawText);
            UnicodeScalar.toCodePoints(analysisNfc);
            tokens = List.copyOf(tokens);
        }

        public int maxTokenUtf8Bytes() {
            return tokens.stream().mapToInt(Token::utf8Length).max().orElse(0);
        }
    }

    private final NfcProvenanceNormalizer normalizer = new NfcProvenanceNormalizer();
    private final Uax29Tokenizer tokenizer = new Uax29Tokenizer();
    private final SimpleLowercaseFilter lowercase = new SimpleLowercaseFilter();

    public AnalyzedText analyze(String rawText) {
        NormalizedText normalized = normalizer.normalize(rawText);
        int[] codePoints = UnicodeScalar.toCodePoints(normalized.text());
        List<Token> tokens = new ArrayList<>();
        for (Segment segment : tokenizer.segments(normalized.text())) {
            if (!tokenizer.emits(codePoints, segment.start(), segment.end())) {
                continue;
            }
            int rawStart = Integer.MAX_VALUE;
            int rawEnd = -1;
            for (int index = segment.start(); index < segment.end(); index++) {
                Provenance provenance = normalized.provenance().get(index);
                rawStart = Math.min(rawStart, provenance.rawStart());
                rawEnd = Math.max(rawEnd, provenance.rawEnd());
            }
            tokens.add(new Token(
                    lowercase.apply(codePoints, segment.start(), segment.end()),
                    tokens.size(),
                    rawStart,
                    rawEnd));
        }
        return new AnalyzedText(rawText, normalized.text(), tokens);
    }

    /** Applies TERM cardinality validation but deliberately not wire error mapping. */
    public Token analyzeTerm(String surface) {
        List<Token> tokens = analyze(surface).tokens();
        if (tokens.size() != 1) {
            throw new IllegalArgumentException(
                    "TERM surface must emit exactly one token, got " + tokens.size());
        }
        return tokens.getFirst();
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        return new TokenStreamComponents(new ContractTokenizer(this));
    }

    private static final class ContractTokenizer extends Tokenizer {
        private final ContractAnalyzer analyzer;
        private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        private final PositionIncrementAttribute positionIncrement =
                addAttribute(PositionIncrementAttribute.class);
        private final OffsetAttribute offset = addAttribute(OffsetAttribute.class);
        private List<Token> tokens = List.of();
        private int[] rawCodePointToUtf16 = new int[] {0};
        private int cursor;

        private ContractTokenizer(ContractAnalyzer analyzer) {
            this.analyzer = analyzer;
        }

        @Override
        public void reset() throws IOException {
            super.reset();
            StringBuilder raw = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                raw.append(buffer, 0, count);
            }
            String rawText = raw.toString();
            rawCodePointToUtf16 = UnicodeScalar.codePointToUtf16Boundaries(rawText);
            tokens = analyzer.analyze(rawText).tokens();
            cursor = 0;
        }

        @Override
        public boolean incrementToken() {
            if (cursor >= tokens.size()) {
                return false;
            }
            clearAttributes();
            Token token = tokens.get(cursor++);
            term.append(token.text());
            positionIncrement.setPositionIncrement(1);
            offset.setOffset(
                    correctOffset(rawCodePointToUtf16[token.rawStart()]),
                    correctOffset(rawCodePointToUtf16[token.rawEnd()]));
            return true;
        }

        @Override
        public void end() throws IOException {
            super.end();
            int finalOffset = correctOffset(rawCodePointToUtf16[rawCodePointToUtf16.length - 1]);
            offset.setOffset(finalOffset, finalOffset);
        }
    }
}
