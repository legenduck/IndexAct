package org.indexact.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.indexact.analysis.ContractAnalyzer;
import org.indexact.analysis.ContractAnalyzer.AnalyzedText;
import org.indexact.analysis.UnicodeScalar;
import org.indexact.index.BuildError.Reason;

/** Validates and seals immutable Lucene snapshots without silent token loss. */
public final class SnapshotBuilder {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    public static final Comparator<String> CODE_POINT_ORDER = SnapshotBuilder::compareCodePoints;

    @FunctionalInterface
    interface WriterFactory {
        IndexWriter open(Directory directory, IndexWriterConfig config) throws IOException;
    }

    private final WriterFactory writerFactory;

    public SnapshotBuilder() {
        this(IndexWriter::new);
    }

    SnapshotBuilder(WriterFactory writerFactory) {
        this.writerFactory = java.util.Objects.requireNonNull(writerFactory, "writerFactory");
    }

    public record InputDocument(String docKey, String rawText) {}

    public enum BuildMode { MEMORY, SPEED }

    public record Options(int flushEveryDocuments, BuildMode mode, int workers,
            int ramBufferMB, int maxInFlightMB) {
        public static final int SPEED_RAM_BUFFER_MB = 4096;
        public static final int SPEED_IN_FLIGHT_MB = 2048;

        public Options {
            if (flushEveryDocuments < 0) {
                throw new IllegalArgumentException("flushEveryDocuments must be non-negative");
            }
            java.util.Objects.requireNonNull(mode, "mode");
            if (workers < 1 || workers > Integer.MAX_VALUE / 2) {
                throw new IllegalArgumentException("workers must be between 1 and 1073741823");
            }
            if (mode == BuildMode.MEMORY && workers != 1) {
                throw new IllegalArgumentException("multiple workers require speed build mode");
            }
            if (ramBufferMB < 1 || maxInFlightMB < 1) {
                throw new IllegalArgumentException("ramBufferMB and maxInFlightMB must be positive");
            }
            if (mode == BuildMode.MEMORY && (ramBufferMB != (int) IndexWriterConfig.DEFAULT_RAM_BUFFER_SIZE_MB
                    || maxInFlightMB != 1)) {
                throw new IllegalArgumentException("buffer tuning requires speed build mode");
            }
        }

        public Options(int flushEveryDocuments, BuildMode mode, int workers) {
            this(flushEveryDocuments, mode, workers,
                    mode == BuildMode.SPEED ? SPEED_RAM_BUFFER_MB : (int) IndexWriterConfig.DEFAULT_RAM_BUFFER_SIZE_MB,
                    mode == BuildMode.SPEED ? SPEED_IN_FLIGHT_MB : 1);
        }

        /** Backwards-compatible, one-document-at-a-time memory mode. */
        public Options(int flushEveryDocuments) {
            this(flushEveryDocuments, BuildMode.MEMORY, 1);
        }

        public static Options defaults() {
            return new Options(0);
        }

        public static Options speed() {
            return speed(Runtime.getRuntime().availableProcessors());
        }

        public static Options speed(int workers) {
            return new Options(0, BuildMode.SPEED, workers);
        }

        long maxInFlightBytes() {
            return (long) maxInFlightMB * 1024 * 1024;
        }
    }

    @FunctionalInterface
    interface DocumentConsumer {
        void accept(InputDocument document) throws BuildError, IOException;
    }

    @FunctionalInterface
    interface DocumentSource {
        void forEach(DocumentConsumer consumer) throws BuildError, IOException;

        default void forEachDeferred(DeferredConsumer consumer) throws BuildError, IOException {
            forEach(input -> consumer.accept(() -> input, input == null ? 0
                    : 2L * ((input.rawText() == null ? 0 : input.rawText().length())
                            + (long) (input.docKey() == null ? 0 : input.docKey().length()))));
        }
    }

    @FunctionalInterface
    interface DocumentLoader {
        InputDocument load() throws BuildError;
    }

    @FunctionalInterface
    interface DeferredConsumer {
        void accept(DocumentLoader loader, long inputBytes) throws BuildError, IOException;
    }

    record BuiltIndex(Manifest manifest, List<String> docKeys) {}

    public Snapshot build(List<InputDocument> documents) throws BuildError {
        return build(documents, Options.defaults());
    }

    public Snapshot build(List<InputDocument> documents, Options options) throws BuildError {
        return build(documents, options, new ByteBuffersDirectory());
    }

    Snapshot build(List<InputDocument> documents, Options options, Directory directory)
            throws BuildError {
        java.util.Objects.requireNonNull(documents, "documents");
        java.util.Objects.requireNonNull(options, "options");
        DirectoryReader reader = null;
        ContractAnalyzer analyzer = null;
        try {
            BuiltIndex built = write(consumer -> {
                for (InputDocument document : documents) {
                    consumer.accept(document);
                }
            }, options, directory);
            reader = DirectoryReader.open(directory);
            analyzer = new ContractAnalyzer();
            return new Snapshot(
                    built.manifest().snapshotId(), built.manifest(), directory, reader,
                    analyzer, built.docKeys());
        } catch (BuildError | IOException | RuntimeException error) {
            if (analyzer != null) {
                analyzer.close();
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
            }
            try {
                directory.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            if (error instanceof BuildError buildError) {
                throw buildError;
            }
            throw new BuildError(Reason.INDEX_IO_FAILURE, "Lucene snapshot build failed", error);
        }
    }

    /** Writes and seals an index without constructing the service's in-memory Snapshot. */
    BuiltIndex write(DocumentSource source, Options options, Directory directory) throws BuildError {
        return write(source, options, directory, BuildProgress.NONE, null);
    }

    BuiltIndex write(DocumentSource source, Options options, Directory directory,
            BuildProgress progress, Long documentCount) throws BuildError {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(options, "options");
        BuildStatistics statistics = new BuildStatistics();
        try (ContractAnalyzer analyzer = new ContractAnalyzer()) {
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                    .setMergePolicy(NoMergePolicy.INSTANCE);
            if (options.mode() == BuildMode.SPEED) {
                config.setRAMBufferSizeMB(options.ramBufferMB());
            }
            String id;
            progress.begin("index_documents", "documents", documentCount);
            try (IndexWriter writer = writerFactory.open(directory, config)) {
                try {
                    if (options.mode() == BuildMode.SPEED) {
                        indexInParallel(source, options, statistics, writer, progress);
                    } else {
                        source.forEach(input -> {
                            statistics.validateKey(input);
                            statistics.add(input,
                                    prepare(input, statistics.documentIds.size(), analyzer), writer);
                            flushIfRequested(options, statistics, writer);
                            progress.advance(1);
                        });
                    }
                    // Read one stored raw document at a time in public DocKey order. The index
                    // is already our immutable disk-backed copy; no corpus-sized text list is needed.
                    progress.begin("flush_index", null, null);
                    try (DirectoryReader indexed = DirectoryReader.open(writer)) {
                        if (options.mode() == BuildMode.SPEED) {
                            resolveIndexedDocumentIds(indexed, statistics.documentIds, progress);
                        }
                        id = contentSnapshotId(indexed, statistics.documentIds, progress);
                    }
                    progress.begin("lucene_commit", null, null);
                    writer.setLiveCommitData(Map.of("snapshot_id", id).entrySet());
                    writer.commit();
                    progress.begin("close_writer", null, null);
                } catch (BuildError | IOException | RuntimeException error) {
                    try {
                        writer.rollback();
                    } catch (IOException | RuntimeException rollbackError) {
                        error.addSuppressed(rollbackError);
                    }
                    throw error;
                }
            }
            progress.begin("sort_manifest_keys", null, null);
            List<String> docKeys = statistics.documentIds.keySet().stream()
                    .sorted(CODE_POINT_ORDER)
                    .toList();
            double avgdl = docKeys.isEmpty()
                    ? 0.0
                    : (double) statistics.sumLen / (double) docKeys.size();
            Map<String, String> checksums = indexChecksums(directory, progress);
            Manifest manifest = new Manifest(
                    id,
                    "3.4",
                    ContractAnalyzer.CONTRACT_VERSION,
                    "SCORING_CONTRACT_v2.2",
                    "raw-codepoint-coordinates-v3.4",
                    ContractAnalyzer.UNICODE_VERSION,
                    ContractAnalyzer.UAX15_REVISION,
                    ContractAnalyzer.UAX29_REVISION,
                    "L-or-N-or-Extended_Pictographic-or-Regional_Indicator",
                    "NFC-at-analysis-with-raw-offset-provenance",
                    "Unicode-17-simple-lowercase",
                    "none",
                    "none",
                    "dense-0-based",
                    Version.LATEST.toString(),
                    docKeys.size(),
                    statistics.sumLen,
                    avgdl,
                    statistics.maxDocKeyBytes,
                    statistics.maxTokenBytes,
                    false,
                    checksums);
            return new BuiltIndex(manifest, docKeys);
        } catch (IOException | RuntimeException error) {
            throw new BuildError(Reason.INDEX_IO_FAILURE, "Lucene snapshot build failed", error);
        }
    }

    private static void indexInParallel(DocumentSource source, Options options,
            BuildStatistics statistics, IndexWriter writer, BuildProgress progress)
            throws BuildError, IOException {
        // Only compact outcomes wait for source-order validation; bodies, analysis and Lucene
        // writes proceed independently. This map cannot stop another worker taking its next job.
        Map<Integer, IndexedDocument> outcomes = new TreeMap<>();
        AtomicInteger indexedCount = new AtomicInteger();
        try (var pipeline = new ParallelBuildPipeline<IndexedDocument>(
                options.workers(), options.maxInFlightBytes(), outcome -> {
                    outcomes.put(outcome.index(), outcome);
                    IndexedDocument next;
                    while ((next = outcomes.remove(statistics.documentIds.size())) != null) {
                        statistics.acceptIndexed(next);
                        progress.advance(1);
                    }
                })) {
            int[] sequence = {0};
            source.forEachDeferred((loader, inputBytes) -> {
                int index = sequence[0]++;
                pipeline.submit(inputBytes, () -> {
                    String docKey = null;
                    try {
                        InputDocument input = loader.load();
                        docKey = input == null ? null : input.docKey();
                        validateDocKey(input, index);
                        try (ContractAnalyzer workerAnalyzer = new ContractAnalyzer()) {
                            PreparedDocument prepared = prepare(input, index, workerAnalyzer);
                            // IndexWriter supports concurrent addDocument calls. No coordinator
                            // serializes token inversion, stored fields or compression anymore.
                            writer.addDocument(prepared.document());
                            int count = indexedCount.incrementAndGet();
                            if (options.flushEveryDocuments() > 0
                                    && count % options.flushEveryDocuments() == 0) {
                                writer.flush();
                            }
                            return new IndexedDocument(index, docKey, prepared.tokenCount(),
                                    prepared.maxTokenBytes(), null);
                        }
                    } catch (BuildError error) {
                        return new IndexedDocument(index, docKey, 0, 0, error);
                    } catch (IOException | RuntimeException error) {
                        // Lucene can reject a document (for example an oversized DocKey).
                        // Keep that failure behind earlier source-row validation as well.
                        return new IndexedDocument(index, docKey, 0, 0, new BuildError(
                                Reason.INDEX_IO_FAILURE, "Lucene snapshot build failed", error));
                    }
                });
            });
            pipeline.drain();
        }
    }

    private record IndexedDocument(int index, String docKey, int tokenCount,
            int maxTokenBytes, BuildError failure) {}

    private static void resolveIndexedDocumentIds(DirectoryReader reader, Map<String, Integer> ids,
            BuildProgress progress)
            throws IOException {
        progress.begin("resolve_document_ids", "documents", (long) ids.size());
        if (reader.numDocs() != ids.size()) {
            throw new IOException("parallel index document count mismatch");
        }
        SortedDocValues keys = MultiDocValues.getSortedValues(reader, FieldSchema.DOC_KEY);
        int found = 0;
        if (keys != null) {
            for (int doc = keys.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = keys.nextDoc()) {
                String key = keys.lookupOrd(keys.ordValue()).utf8ToString();
                if (ids.replace(key, doc) == null) {
                    throw new IOException("parallel index contains an unexpected DocKey");
                }
                found++;
                progress.advance(1);
            }
        }
        if (found != ids.size()) {
            throw new IOException("parallel index is missing DocKey values");
        }
    }

    private static void flushIfRequested(Options options, BuildStatistics statistics,
            IndexWriter writer) throws IOException {
        if (options.flushEveryDocuments() > 0
                && statistics.documentIds.size() % options.flushEveryDocuments() == 0) {
            writer.flush();
        }
    }

    private static void validateDocKey(InputDocument input, int index) throws BuildError {
        if (input == null || input.docKey() == null) {
            throw new BuildError(Reason.INVALID_DOC_KEY, "document " + index + " has no DocKey");
        }
        try {
            UnicodeScalar.toCodePoints(input.docKey());
        } catch (IllegalArgumentException error) {
            throw new BuildError(Reason.INVALID_DOC_KEY, "invalid DocKey at document " + index, error);
        }
    }

    private record PreparedDocument(Document document, int tokenCount, int maxTokenBytes) {}

    private static PreparedDocument prepare(InputDocument input, int index,
            ContractAnalyzer analyzer) throws BuildError {
        if (input.rawText() == null) {
            throw new BuildError(Reason.INVALID_RAW_TEXT, "document " + index + " has no raw text");
        }
        final AnalyzedText analyzed;
        try {
            analyzed = analyzer.analyze(input.rawText());
        } catch (IllegalArgumentException error) {
            throw new BuildError(Reason.INVALID_RAW_TEXT, "invalid raw text at document " + index, error);
        }
        int maxTokenBytes = 0;
        for (ContractAnalyzer.Token token : analyzed.tokens()) {
            int tokenBytes = token.utf8Length();
            if (tokenBytes > IndexWriter.MAX_TERM_LENGTH) {
                throw new BuildError(Reason.TOKEN_TOO_LONG,
                        "analyzed token exceeds Lucene's " + IndexWriter.MAX_TERM_LENGTH
                                + " byte limit in DocKey " + input.docKey());
            }
            maxTokenBytes = Math.max(maxTokenBytes, tokenBytes);
        }
        return new PreparedDocument(luceneDocument(input, analyzed),
                analyzed.tokens().size(), maxTokenBytes);
    }

    private static final class BuildStatistics {
        private final Map<String, Integer> documentIds = new HashMap<>();
        private long sumLen;
        private int maxDocKeyBytes;
        private int maxTokenBytes;

        void acceptIndexed(IndexedDocument result) throws BuildError {
            if (result.failure() != null && (result.failure().reason() == Reason.INVALID_DOC_KEY
                    || result.failure().reason() == Reason.INVALID_INPUT_RECORD)) {
                throw result.failure();
            }
            if (documentIds.containsKey(result.docKey())) {
                throw new BuildError(Reason.DUPLICATE_DOC_KEY, "duplicate DocKey: " + result.docKey());
            }
            if (result.failure() != null) {
                throw result.failure();
            }
            sumLen += result.tokenCount();
            if (sumLen > MAX_SAFE_INTEGER) {
                throw new BuildError(Reason.NUMERIC_LIMIT_EXCEEDED, "sum_len_tokens is not I-JSON safe");
            }
            maxTokenBytes = Math.max(maxTokenBytes, result.maxTokenBytes());
            maxDocKeyBytes = Math.max(maxDocKeyBytes, result.docKey().getBytes(StandardCharsets.UTF_8).length);
            // Temporary input ordinal, replaced with the actual Lucene doc ID after all writes.
            documentIds.put(result.docKey(), result.index());
        }

        void validateKey(InputDocument input) throws BuildError {
            validateDocKey(input, documentIds.size());
            if (documentIds.containsKey(input.docKey())) {
                throw new BuildError(Reason.DUPLICATE_DOC_KEY, "duplicate DocKey: " + input.docKey());
            }
        }

        void add(InputDocument input, PreparedDocument prepared, IndexWriter writer)
                throws BuildError, IOException {
            int index = documentIds.size();
            maxTokenBytes = Math.max(maxTokenBytes, prepared.maxTokenBytes());
            sumLen += prepared.tokenCount();
            if (sumLen > MAX_SAFE_INTEGER) {
                throw new BuildError(
                        Reason.NUMERIC_LIMIT_EXCEEDED, "sum_len_tokens is not I-JSON safe");
            }
            writer.addDocument(prepared.document());
            documentIds.put(input.docKey(), index);
            maxDocKeyBytes = Math.max(
                    maxDocKeyBytes, input.docKey().getBytes(StandardCharsets.UTF_8).length);
        }
    }

    static Document luceneDocument(InputDocument input, AnalyzedText analyzed) {
        Document document = new Document();
        document.add(new StringField(FieldSchema.DOC_KEY, input.docKey(), Field.Store.YES));
        document.add(new SortedDocValuesField(FieldSchema.DOC_KEY, new BytesRef(input.docKey())));
        int[] rawBoundaries = UnicodeScalar.codePointToUtf16Boundaries(input.rawText());
        document.add(new Field(FieldSchema.BODY,
                new AnalyzedTokenStream(analyzed.tokens(), rawBoundaries), FieldSchema.BODY_TYPE));
        document.add(new StoredField(FieldSchema.RAW, input.rawText()));
        document.add(new NumericDocValuesField(
                FieldSchema.LEN_TOKENS, analyzed.tokens().size()));
        document.add(new NumericDocValuesField(
                FieldSchema.LEN_CODEPOINTS, input.rawText().codePointCount(0, input.rawText().length())));
        document.add(new StoredField(FieldSchema.TOKEN_SPANS,
                TokenSpanCodec.encodeTokens(analyzed.tokens(), rawBoundaries)));
        return document;
    }

    /** Replays the validated analysis instead of tokenizing the body a second time. */
    private static final class AnalyzedTokenStream extends TokenStream {
        private List<ContractAnalyzer.Token> tokens;
        private int[] boundaries;
        private int cursor;
        private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        private final PositionIncrementAttribute increment = addAttribute(PositionIncrementAttribute.class);
        private final OffsetAttribute offset = addAttribute(OffsetAttribute.class);

        AnalyzedTokenStream(List<ContractAnalyzer.Token> tokens, int[] boundaries) {
            this.tokens = tokens;
            this.boundaries = boundaries;
        }

        @Override
        public void reset() throws IOException {
            super.reset();
            cursor = 0;
        }

        @Override
        public boolean incrementToken() {
            if (cursor == tokens.size()) {
                return false;
            }
            clearAttributes();
            ContractAnalyzer.Token token = tokens.get(cursor++);
            term.append(token.text());
            increment.setPositionIncrement(1);
            offset.setOffset(boundaries[token.rawStart()], boundaries[token.rawEnd()]);
            return true;
        }

        @Override
        public void end() throws IOException {
            super.end();
            int end = boundaries[boundaries.length - 1];
            offset.setOffset(end, end);
        }

        @Override
        public void close() throws IOException {
            tokens = List.of();
            boundaries = new int[] {0};
            super.close();
        }
    }

    private static String contentSnapshotId(DirectoryReader reader, Map<String, Integer> documents,
            BuildProgress progress)
            throws IOException {
        progress.begin("sort_content_keys", null, null);
        List<String> keys = documents.keySet().stream().sorted(CODE_POINT_ORDER).toList();
        progress.begin("hash_content", "documents", (long) keys.size());
        MessageDigest digest = sha256();
        digest.update("IndexActSnapshot\0v3.4\0".getBytes(StandardCharsets.UTF_8));
        var storedFields = reader.storedFields();
        for (String key : keys) {
            Document stored = storedFields.document(documents.get(key), Set.of(FieldSchema.RAW));
            updateLengthPrefixed(digest, key);
            updateLengthPrefixed(digest, stored.get(FieldSchema.RAW));
            progress.advance(1);
        }
        return "snap-" + HexFormat.of().formatHex(digest.digest());
    }

    static String contentSnapshotId(List<InputDocument> documents) {
        MessageDigest digest = sha256();
        digest.update("IndexActSnapshot\0v3.4\0".getBytes(StandardCharsets.UTF_8));
        documents.stream().sorted(Comparator.comparing(InputDocument::docKey, CODE_POINT_ORDER))
                .forEach(document -> {
                    updateLengthPrefixed(digest, document.docKey());
                    updateLengthPrefixed(digest, document.rawText());
                });
        return "snap-" + HexFormat.of().formatHex(digest.digest());
    }

    private static void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(encoded.length).array());
        digest.update(encoded);
    }

    private static Map<String, String> indexChecksums(Directory directory, BuildProgress progress)
            throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(List.of(directory.listAll()));
        names.sort(CODE_POINT_ORDER);
        long bytes = 0;
        for (String name : names) {
            bytes += directory.fileLength(name);
        }
        progress.begin("index_checksums", "bytes", bytes);
        byte[] buffer = new byte[8192];
        for (String name : names) {
            progress.file(name);
            MessageDigest digest = sha256();
            try (IndexInput input = directory.openInput(name, IOContext.READONCE)) {
                long remaining = input.length();
                while (remaining > 0) {
                    int count = (int) Math.min(buffer.length, remaining);
                    input.readBytes(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    remaining -= count;
                    progress.advance(count);
                }
            }
            result.put(name, HexFormat.of().formatHex(digest.digest()));
        }
        return Map.copyOf(result);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError("SHA-256 is required by Java", error);
        }
    }

    static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = left.codePointAt(leftIndex);
            int rightCodePoint = right.codePointAt(rightIndex);
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }
}
