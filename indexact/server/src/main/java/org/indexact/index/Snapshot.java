package org.indexact.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.indexact.analysis.ContractAnalyzer;
import org.indexact.read.ReadEngine;

/** One immutable, deletion-free Lucene snapshot and its exact public statistics. */
public final class Snapshot implements AutoCloseable {
    /** Immutable non-text metadata for one dense analyzed-token position. */
    public record TokenSpan(int rawStart, int rawEnd, int utf16Start, int utf16End) {
        public TokenSpan {
            if (rawStart < 0 || rawEnd <= rawStart || utf16Start < 0 || utf16End <= utf16Start) {
                throw new IllegalArgumentException("invalid token span");
            }
        }
    }

    private final String snapshotId;
    private final Manifest manifest;
    private final Directory directory;
    private final DirectoryReader reader;
    private final Analyzer analyzer;
    private final IndexSearcher searcher;
    private final List<LeafReaderContext> leaves;
    private final List<String> docKeys;
    private final Map<String, Integer> internalDocByKey;
    private final List<String> keyByInternalDoc;
    private final LongAdder rawStoredFieldLoads = new LongAdder();
    private final LongAdder tokenSpanStoredFieldLoads = new LongAdder();
    private final LongAdder occurrencePostingsCursorOpens = new LongAdder();
    private final LongAdder documentLengthCursorOpens = new LongAdder();
    private final LongAdder docKeyListAccesses = new LongAdder();

    Snapshot(
            String snapshotId,
            Manifest manifest,
            Directory directory,
            DirectoryReader reader,
            Analyzer analyzer,
            List<String> docKeys)
            throws IOException {
        this.snapshotId = java.util.Objects.requireNonNull(snapshotId, "snapshotId");
        this.manifest = java.util.Objects.requireNonNull(manifest, "manifest");
        if (!snapshotId.equals(manifest.snapshotId())) {
            throw new IOException("snapshot identity disagrees with its manifest");
        }
        this.directory = directory;
        this.reader = reader;
        this.analyzer = analyzer;
        this.searcher = new IndexSearcher(reader);
        this.leaves = List.copyOf(reader.leaves());
        this.docKeys = List.copyOf(docKeys);
        if (reader.hasDeletions() || reader.numDocs() != reader.maxDoc()) {
            throw new IOException("served snapshots must not contain deleted documents");
        }
        Map<String, Integer> internalByKey = new HashMap<>();
        List<String> keyByDocument = new ArrayList<>(reader.maxDoc());
        SortedDocValues orderedKeys = MultiDocValues.getSortedValues(reader, FieldSchema.DOC_KEY);
        if (reader.maxDoc() > 0 && orderedKeys == null) {
            throw new IOException("snapshot is missing sorted DocKey doc values");
        }
        for (int documentId = 0; documentId < reader.maxDoc(); documentId++) {
            Document metadata = reader.storedFields().document(
                    documentId, Set.of(FieldSchema.DOC_KEY));
            String key = metadata.get(FieldSchema.DOC_KEY);
            if (!orderedKeys.advanceExact(documentId)
                    || !orderedKeys.lookupOrd(orderedKeys.ordValue()).utf8ToString().equals(key)) {
                throw new IOException("stored and sorted-doc-values DocKeys disagree");
            }
            if (key == null || internalByKey.put(key, documentId) != null) {
                throw new IOException("corrupt or duplicate stored DocKey");
            }
            keyByDocument.add(key);
        }
        if (!internalByKey.keySet().equals(Set.copyOf(docKeys))) {
            throw new IOException("manifest DocKey set does not equal the Lucene index");
        }
        this.internalDocByKey = Map.copyOf(internalByKey);
        this.keyByInternalDoc = List.copyOf(keyByDocument);
    }

    public String snapshotId() {
        return snapshotId;
    }

    public Manifest manifest() {
        return manifest;
    }

    public List<String> docKeys() {
        docKeyListAccesses.increment();
        return docKeys;
    }

    /** Diagnostic for accidental corpus-wide validation on bounded READ paths. */
    public long docKeyListAccessCount() { return docKeyListAccesses.sum(); }

    /** Constant-time lookup in the already validated immutable snapshot. */
    public boolean containsDocKey(String docKey) {
        return internalDocByKey.containsKey(docKey);
    }

    /** One request-owned cursor; never share mutable Lucene cursors between requests. */
    public NumericDocValues documentLengths() throws IOException {
        documentLengthCursorOpens.increment();
        return MultiDocValues.getNumericValues(reader, FieldSchema.LEN_TOKENS);
    }

    public long documentLengthCursorOpenCount() {
        return documentLengthCursorOpens.sum();
    }

    public int segmentCount() {
        return leaves.size();
    }

    /** Internal execution primitive; Lucene identities never cross the wire boundary. */
    public IndexSearcher searcher() {
        return searcher;
    }

    public int maxDocumentId() {
        return reader.maxDoc();
    }

    public int leafCount() {
        return leaves.size();
    }

    public int leafDocumentBase(int leafOrdinal) {
        return leaves.get(leafOrdinal).docBase;
    }

    public int leafMaxDoc(int leafOrdinal) {
        return leaves.get(leafOrdinal).reader().maxDoc();
    }

    public int leafOrdinal(int globalDocument) {
        if (globalDocument < 0 || globalDocument >= reader.maxDoc()) {
            throw new IllegalArgumentException("Lucene document is outside this snapshot");
        }
        return org.apache.lucene.index.ReaderUtil.subIndex(globalDocument, leaves);
    }

    public int internalDocumentId(String docKey) {
        return requireInternalDocument(docKey);
    }

    public String docKeyForInternalDocument(int documentId) {
        if (documentId < 0 || documentId >= keyByInternalDoc.size()) {
            throw new IllegalArgumentException("Lucene document is outside this snapshot");
        }
        return keyByInternalDoc.get(documentId);
    }

    public int documentFrequency(String analyzedToken) throws IOException {
        return reader.docFreq(new Term(FieldSchema.BODY, analyzedToken));
    }

    public long documentLength(String docKey) throws IOException {
        int documentId = requireInternalDocument(docKey);
        NumericDocValues values = MultiDocValues.getNumericValues(reader, FieldSchema.LEN_TOKENS);
        if (values == null || !values.advanceExact(documentId)) {
            throw new IOException("snapshot is missing len_tokens for " + docKey);
        }
        return values.longValue();
    }

    public int rawCodePointLength(String docKey) throws IOException {
        int documentId = requireInternalDocument(docKey);
        NumericDocValues values = MultiDocValues.getNumericValues(reader, FieldSchema.LEN_CODEPOINTS);
        if (values == null || !values.advanceExact(documentId)) {
            throw new IOException("snapshot is missing len_codepoints for " + docKey);
        }
        return Math.toIntExact(values.longValue());
    }

    /** Mints the sole production capability that can reach the raw stored field. */
    public ReadEngine newReadEngine() {
        return ReadEngine.owned(this, new ContractAnalyzer(), (ignored, docKey) -> loadRawText(docKey));
    }

    private String loadRawText(String docKey) throws IOException {
        int documentId = requireInternalDocument(docKey);
        rawStoredFieldLoads.increment();
        Document document = reader.storedFields().document(documentId, Set.of(FieldSchema.RAW));
        String raw = document.get(FieldSchema.RAW);
        if (raw == null) {
            throw new IOException("snapshot is missing raw text for " + docKey);
        }
        return raw;
    }

    /** Diagnostic counter used to enforce the raw-materialization boundary in tests. */
    public long rawStoredFieldLoadCount() {
        return rawStoredFieldLoads.sum();
    }

    /** Loads only this document's coordinates; callers retain them for the current read. */
    public List<TokenSpan> tokenSpans(String docKey) throws IOException {
        int documentId = requireInternalDocument(docKey);
        tokenSpanStoredFieldLoads.increment();
        Document metadata = reader.storedFields().document(documentId, Set.of(FieldSchema.TOKEN_SPANS));
        BytesRef encoded = metadata.getBinaryValue(FieldSchema.TOKEN_SPANS);
        if (encoded == null) {
            throw new IOException("snapshot is missing token-span metadata");
        }
        return TokenSpanCodec.decode(java.util.Arrays.copyOfRange(
                encoded.bytes, encoded.offset, encoded.offset + encoded.length));
    }

    /** Diagnostic: search predicates must not materialize raw-coordinate tables. */
    public long tokenSpanStoredFieldLoadCount() { return tokenSpanStoredFieldLoads.sum(); }

    public int termFrequency(String docKey, String analyzedToken) throws IOException {
        int documentId = requireInternalDocument(docKey);
        PostingsEnum postings = termFrequencyPostings(analyzedToken);
        return postings != null && postings.advance(documentId) == documentId ? postings.freq() : 0;
    }

    /** Token positions alone suffice for lexical predicates; no stored fields are read. */
    public int[] termPositions(String docKey, String analyzedToken) throws IOException {
        int documentId = requireInternalDocument(docKey);
        PostingsEnum postings = MultiTerms.getTermPostingsEnum(
                reader, FieldSchema.BODY, new BytesRef(analyzedToken), PostingsEnum.POSITIONS);
        if (postings == null || postings.advance(documentId) != documentId) {
            return new int[0];
        }
        return decodeTermPositions(postings, documentLengthByInternalDocument(documentId));
    }

    /**
     * Opens one forward-only positional cursor for exact condition execution.
     *
     * <p>The cursor is deliberately request-scoped. It lets a FILTER or COUNT_DOCS operation
     * traverse one term's postings once instead of reopening and advancing the same postings list
     * independently for every candidate document.
     */
    public TermPositionCursor newTermPositionCursor(String analyzedToken) throws IOException {
        occurrencePostingsCursorOpens.increment();
        return new TermPositionCursor(MultiTerms.getTermPostingsEnum(
                reader, FieldSchema.BODY, new BytesRef(analyzedToken), PostingsEnum.POSITIONS),
                documentLengths());
    }

    /** Diagnostic counter for execution-architecture regression tests. */
    public long occurrencePostingsCursorOpenCount() {
        return occurrencePostingsCursorOpens.sum();
    }

    /** One monotonic traversal of a term's positional postings. */
    public final class TermPositionCursor {
        private final PostingsEnum postings;
        private final NumericDocValues lengths;
        private int lastRequestedDocument = -1;
        private int[] lastResult = new int[0];

        private TermPositionCursor(PostingsEnum postings, NumericDocValues lengths) {
            this.postings = postings;
            this.lengths = lengths;
        }

        /** The returned request-local array must not be modified by callers. */
        public int[] positionsAt(int documentId) throws IOException {
            if (documentId < 0 || documentId >= reader.maxDoc()) {
                throw new IllegalArgumentException("Lucene document is outside this snapshot");
            }
            if (documentId < lastRequestedDocument) {
                throw new IllegalArgumentException(
                        "term occurrence cursor documents must be requested monotonically");
            }
            if (documentId == lastRequestedDocument) {
                return lastResult;
            }
            lastRequestedDocument = documentId;
            if (postings == null) {
                lastResult = new int[0];
                return lastResult;
            }
            int current = postings.docID();
            if (current < documentId) {
                current = postings.advance(documentId);
            }
            if (current == documentId) {
                if (lengths == null || !lengths.advanceExact(documentId)) {
                    throw new IOException("snapshot is missing len_tokens for internal document");
                }
                lastResult = decodeTermPositions(postings, lengths.longValue());
            } else {
                lastResult = new int[0];
            }
            return lastResult;
        }
    }

    private static int[] decodeTermPositions(PostingsEnum postings, long tokenCount) throws IOException {
        int[] positions = new int[postings.freq()];
        for (int index = 0; index < positions.length; index++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("term position traversal interrupted");
            }
            int position = postings.nextPosition();
            if (position < 0 || position >= tokenCount) {
                throw new IOException("token position is outside dense token metadata: " + position);
            }
            positions[index] = position;
        }
        return positions;
    }

    /** Opens one reusable postings cursor for canonical scoring execution. */
    public PostingsEnum termFrequencyPostings(String analyzedToken) throws IOException {
        return MultiTerms.getTermPostingsEnum(
                reader, FieldSchema.BODY, new BytesRef(analyzedToken), PostingsEnum.FREQS);
    }

    public long documentLengthByInternalDocument(int documentId) throws IOException {
        if (documentId < 0 || documentId >= reader.maxDoc()) {
            throw new IllegalArgumentException("Lucene document is outside this snapshot");
        }
        NumericDocValues values = MultiDocValues.getNumericValues(reader, FieldSchema.LEN_TOKENS);
        if (values == null || !values.advanceExact(documentId)) {
            throw new IOException("snapshot is missing len_tokens for internal document");
        }
        return values.longValue();
    }

    public List<String> documentsContaining(String analyzedToken) throws IOException {
        PostingsEnum postings = MultiTerms.getTermPostingsEnum(
                reader, FieldSchema.BODY, new BytesRef(analyzedToken), PostingsEnum.NONE);
        if (postings == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int documentId = postings.nextDoc();
                documentId != DocIdSetIterator.NO_MORE_DOCS;
                documentId = postings.nextDoc()) {
            result.add(keyByInternalDoc.get(documentId));
        }
        result.sort(SnapshotBuilder.CODE_POINT_ORDER);
        return List.copyOf(result);
    }

    private int requireInternalDocument(String docKey) {
        Integer documentId = internalDocByKey.get(docKey);
        if (documentId == null) {
            throw new NoSuchElementException("unknown DocKey: " + docKey);
        }
        return documentId;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            reader.close();
        } catch (IOException error) {
            failure = error;
        }
        analyzer.close();
        try {
            directory.close();
        } catch (IOException error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
