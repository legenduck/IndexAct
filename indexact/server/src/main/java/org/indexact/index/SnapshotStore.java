package org.indexact.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.indexact.analysis.ContractAnalyzer;
import org.indexact.analysis.UnicodeScalar;
import org.indexact.index.SnapshotBuilder.InputDocument;
import org.indexact.index.SnapshotOpenException.Reason;

/** Persistent immutable snapshot construction, verification, and opening. */
public final class SnapshotStore {
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String INDEX_DIRECTORY = "index";

    private static final Pattern SNAPSHOT_ID = Pattern.compile("snap-[0-9a-f]{64}");
    private static final Object PUBLICATION_LOCK = new Object();

    private record ValidatedIndex(List<String> docKeys) {}

    private final Path root;
    private final SnapshotBuilder builder;

    public SnapshotStore(Path root) {
        this(root, new SnapshotBuilder());
    }

    SnapshotStore(Path root, SnapshotBuilder builder) {
        this.root = root.toAbsolutePath().normalize();
        this.builder = builder;
    }

    public Path root() {
        return root;
    }

    public String buildJsonLines(Path input) throws BuildError {
        return buildJsonLines(input, SnapshotBuilder.Options.defaults());
    }

    public String buildJsonLines(Path input, SnapshotBuilder.Options options) throws BuildError {
        return buildJsonLines(input, options, BuildProgress.NONE);
    }

    String buildJsonLines(Path input, SnapshotBuilder.Options options, BuildProgress progress)
            throws BuildError {
        return build((temporary, directory) -> {
            Path frozenInput = temporary.resolve("corpus.jsonl");
            progress.begin("validate_and_freeze_input", "records", null);
            long count = JsonLinesCorpus.copyValidated(input, frozenInput, options, progress);
            Manifest manifest = builder.write(JsonLinesCorpus.source(frozenInput), options, directory,
                            progress, count)
                    .manifest();
            Files.delete(frozenInput);
            return manifest;
        }, progress);
    }

    public String build(List<InputDocument> documents) throws BuildError {
        return build(documents, SnapshotBuilder.Options.defaults());
    }

    public String build(List<InputDocument> documents, SnapshotBuilder.Options options)
            throws BuildError {
        return build((temporary, directory) -> builder.write(consumer -> {
            for (InputDocument document : documents) {
                consumer.accept(document);
            }
        }, options, directory).manifest());
    }

    @FunctionalInterface
    private interface BuildAction {
        Manifest write(Path temporary, Directory directory) throws BuildError, IOException;
    }

    private String build(BuildAction action) throws BuildError {
        return build(action, BuildProgress.NONE);
    }

    private String build(BuildAction action, BuildProgress progress) throws BuildError {
        Path temporary = null;
        progress.begin("prepare_store", null, null);
        try {
            Files.createDirectories(root);
            temporary = Files.createTempDirectory(root, ".snapshot-build-");
            Path indexPath = Files.createDirectory(temporary.resolve(INDEX_DIRECTORY));

            Manifest builtManifest;
            try (Directory directory = FSDirectory.open(indexPath)) {
                builtManifest = action.write(temporary, directory);
            }

            Files.deleteIfExists(indexPath.resolve("write.lock"));
            Manifest persisted = builtManifest.withIndexChecksums(checksums(indexPath, progress));
            progress.begin("write_manifest", null, null);
            writeDurably(temporary.resolve(MANIFEST_FILE), ManifestCodec.encode(persisted));
            forceTree(indexPath, progress);
            progress.begin("sync_directories", null, null);
            forceDirectory(indexPath);
            forceDirectory(temporary);
            forceDirectory(root);

            Path destination = root.resolve(persisted.snapshotId());
            publish(temporary, destination, persisted.snapshotId(), progress);
            temporary = null;
            progress.succeeded(persisted.snapshotId());
            return persisted.snapshotId();
        } catch (BuildError error) {
            progress.failed(error.reason());
            throw error;
        } catch (IOException | RuntimeException error) {
            progress.failed(BuildError.Reason.INDEX_IO_FAILURE);
            throw new BuildError(
                    BuildError.Reason.INDEX_IO_FAILURE,
                    "persistent snapshot construction failed",
                    error);
        } finally {
            if (temporary != null) {
                try {
                    makeTreeWritableForCleanup(temporary);
                    deleteTree(temporary);
                } catch (IOException ignored) {
                    // The primary exception remains authoritative; a stale hidden temp is never served.
                }
            }
        }
    }

    public Snapshot open(String snapshotId) throws SnapshotOpenException {
        return open(snapshotId, true);
    }

    private Snapshot open(String snapshotId, boolean loadSnapshot) throws SnapshotOpenException {
        if (snapshotId == null || !SNAPSHOT_ID.matcher(snapshotId).matches()) {
            throw new SnapshotOpenException(Reason.SNAPSHOT_NOT_FOUND, "snapshot is not registered");
        }
        Path snapshotPath = root.resolve(snapshotId);
        if (!Files.exists(snapshotPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new SnapshotOpenException(Reason.SNAPSHOT_NOT_FOUND, "snapshot is not registered");
        }
        if (!Files.isDirectory(snapshotPath, LinkOption.NOFOLLOW_LINKS)) {
            throw corrupt("snapshot artifact is not a directory", null);
        }

        final Manifest manifest;
        try {
            Path manifestPath = snapshotPath.resolve(MANIFEST_FILE);
            if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("snapshot manifest is absent or not a regular file");
            }
            manifest = ManifestCodec.decode(Files.readAllBytes(manifestPath));
        } catch (ManifestCodec.IncompatibleManifestException error) {
            throw new SnapshotOpenException(
                    Reason.SNAPSHOT_INCOMPATIBLE,
                    "snapshot manifest numeric domains are unsupported",
                    error);
        } catch (IOException | RuntimeException error) {
            throw corrupt("snapshot manifest is corrupt", error);
        }
        if (!snapshotId.equals(manifest.snapshotId())) {
            throw corrupt("snapshot directory and manifest identities disagree", null);
        }
        requireSupportedContracts(manifest);

        Path indexPath = snapshotPath.resolve(INDEX_DIRECTORY);
        try {
            verifyChecksums(indexPath, manifest.indexChecksums());
        } catch (IOException | RuntimeException error) {
            throw corrupt("snapshot index checksum verification failed", error);
        }

        Directory directory = null;
        DirectoryReader reader = null;
        Analyzer analyzer = null;
        try {
            directory = FSDirectory.open(indexPath);
            reader = DirectoryReader.open(directory);
            if (!snapshotId.equals(reader.getIndexCommit().getUserData().get("snapshot_id"))) {
                throw new IOException("snapshot content identity does not match its Lucene commit");
            }
            ValidatedIndex validated = validateIndex(reader, manifest);
            if (!loadSnapshot) {
                reader.close();
                reader = null;
                directory.close();
                directory = null;
                return null;
            }
            analyzer = new ContractAnalyzer();
            Snapshot result = new Snapshot(
                    snapshotId, manifest, directory, reader, analyzer, validated.docKeys());
            directory = null;
            reader = null;
            analyzer = null;
            return result;
        } catch (IOException | RuntimeException error) {
            closeAfterFailedOpen(reader, analyzer, directory, error);
            throw corrupt("snapshot index is corrupt", error);
        }
    }

    public List<String> listSnapshotIds() throws SnapshotOpenException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> SNAPSHOT_ID.matcher(name).matches())
                    .sorted()
                    .toList();
        } catch (IOException error) {
            throw corrupt("snapshot store cannot be listed", error);
        }
    }

    private void publish(Path temporary, Path destination, String snapshotId, BuildProgress progress)
            throws IOException {
        progress.begin("publication_lock", null, null);
        Path locks = root.resolve(".locks");
        Files.createDirectories(locks);
        Path lockPath = locks.resolve(snapshotId + ".lock");
        synchronized (PUBLICATION_LOCK) {
            try (FileChannel channel = FileChannel.open(
                            lockPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    progress.begin("verify_existing_snapshot", null, null);
                    verifyExistingForId(snapshotId);
                    progress.begin("discard_duplicate_build", null, null);
                    deleteTree(temporary);
                    return;
                }
                progress.begin("seal_snapshot", null, null);
                sealTree(temporary);
                progress.begin("publish_snapshot", null, null);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, destination);
                }
                forceDirectory(root);
            }
        }
    }

    private void verifyExistingForId(String snapshotId) throws IOException {
        try {
            // Validate the same manifest, checksums and metadata as open(), but do not hydrate
            // every TokenSpan merely to confirm an idempotent build of a published snapshot.
            open(snapshotId, false);
        } catch (SnapshotOpenException error) {
            throw new IOException("existing snapshot artifact failed verification", error);
        }
    }

    private static ValidatedIndex validateIndex(DirectoryReader reader, Manifest manifest)
            throws IOException {
        if (reader.hasDeletions() || reader.numDocs() != reader.maxDoc()) {
            throw new IOException("served snapshots must not contain deletions");
        }
        if (reader.numDocs() != manifest.documentCount()) {
            throw new IOException("manifest document count disagrees with the index");
        }
        NumericDocValues tokenLengths =
                MultiDocValues.getNumericValues(reader, FieldSchema.LEN_TOKENS);
        NumericDocValues rawLengths =
                MultiDocValues.getNumericValues(reader, FieldSchema.LEN_CODEPOINTS);
        SortedDocValues orderedKeys = MultiDocValues.getSortedValues(reader, FieldSchema.DOC_KEY);
        if (reader.maxDoc() > 0 && (tokenLengths == null || rawLengths == null || orderedKeys == null)) {
            throw new IOException("snapshot is missing required numeric doc values");
        }

        long sumTokenLengths = 0;
        int maxDocKeyBytes = 0;
        Set<String> uniqueKeys = new HashSet<>();
        List<String> docKeys = new ArrayList<>(reader.maxDoc());
        for (int documentId = 0; documentId < reader.maxDoc(); documentId++) {
            Document stored = reader.storedFields().document(documentId, Set.of(
                    FieldSchema.DOC_KEY, FieldSchema.TOKEN_SPANS));
            String docKey = stored.get(FieldSchema.DOC_KEY);
            BytesRef encodedSpans = stored.getBinaryValue(FieldSchema.TOKEN_SPANS);
            if (docKey == null || encodedSpans == null || !uniqueKeys.add(docKey)) {
                throw new IOException("snapshot is missing required or unique stored fields");
            }
            UnicodeScalar.toCodePoints(docKey);
            if (!orderedKeys.advanceExact(documentId)
                    || !orderedKeys.lookupOrd(orderedKeys.ordValue()).utf8ToString().equals(docKey)) {
                throw new IOException("stored and sorted-doc-values DocKeys disagree");
            }
            if (!tokenLengths.advanceExact(documentId) || !rawLengths.advanceExact(documentId)) {
                throw new IOException("snapshot is missing required document statistics");
            }
            long tokenLength = tokenLengths.longValue();
            long rawLength = rawLengths.longValue();
            if (tokenLength < 0
                    || tokenLength > SnapshotBuilder.MAX_SAFE_INTEGER
                    || rawLength < 0
                    || rawLength > SnapshotBuilder.MAX_SAFE_INTEGER) {
                throw new IOException("invalid per-document public statistics");
            }
            TokenSpanCodec.validate(encodedSpans, tokenLength, rawLength);
            sumTokenLengths = Math.addExact(sumTokenLengths, tokenLength);
            if (sumTokenLengths > SnapshotBuilder.MAX_SAFE_INTEGER) {
                throw new IOException("sum_len_tokens exceeds the public integer domain");
            }
            maxDocKeyBytes = Math.max(
                    maxDocKeyBytes, docKey.getBytes(StandardCharsets.UTF_8).length);
            docKeys.add(docKey);
        }

        int maxTokenBytes = 0;
        Terms terms = MultiTerms.getTerms(reader, FieldSchema.BODY);
        if (terms != null) {
            TermsEnum iterator = terms.iterator();
            for (BytesRef term = iterator.next(); term != null; term = iterator.next()) {
                maxTokenBytes = Math.max(maxTokenBytes, term.length);
            }
        }
        double avgdl = reader.numDocs() == 0
                ? 0.0
                : (double) sumTokenLengths / (double) reader.numDocs();
        if (sumTokenLengths != manifest.sumLenTokens()
                || Double.doubleToRawLongBits(avgdl) != Double.doubleToRawLongBits(manifest.avgdl())
                || maxDocKeyBytes != manifest.maxDocKeyUtf8BytesObserved()
                || maxTokenBytes != manifest.maxAnalyzedTokenUtf8BytesObserved()) {
            throw new IOException("manifest statistics disagree with the index");
        }
        docKeys.sort(SnapshotBuilder.CODE_POINT_ORDER);
        return new ValidatedIndex(List.copyOf(docKeys));
    }

    private static void requireSupportedContracts(Manifest manifest)
            throws SnapshotOpenException {
        boolean supported = manifest.corpusVersion().equals("3.4")
                && manifest.analyzerContractVersion().equals(ContractAnalyzer.CONTRACT_VERSION)
                && manifest.scoringContractVersion().equals("SCORING_CONTRACT_v2.2")
                && manifest.coordinateContractVersion().equals("raw-codepoint-coordinates-v3.4")
                && manifest.unicodeVersion().equals(ContractAnalyzer.UNICODE_VERSION)
                && manifest.uax15Revision() == ContractAnalyzer.UAX15_REVISION
                && manifest.uax29Revision() == ContractAnalyzer.UAX29_REVISION
                && manifest.emissionProfile()
                        .equals("L-or-N-or-Extended_Pictographic-or-Regional_Indicator")
                && manifest.normalization().equals("NFC-at-analysis-with-raw-offset-provenance")
                && manifest.caseMapping().equals("Unicode-17-simple-lowercase")
                && manifest.stemming().equals("none")
                && manifest.stopwords().equals("none")
                && manifest.positionScheme().equals("dense-0-based")
                && manifest.luceneVersion().equals(Version.LATEST.toString());
        if (!supported) {
            throw new SnapshotOpenException(
                    Reason.SNAPSHOT_INCOMPATIBLE, "snapshot contract versions are unsupported");
        }
    }

    private static Map<String, String> checksums(Path indexPath) throws IOException {
        return checksums(indexPath, BuildProgress.NONE);
    }

    private static Map<String, String> checksums(Path indexPath, BuildProgress progress) throws IOException {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        List<Path> files = indexFiles(indexPath);
        long bytes = 0;
        for (Path file : files) {
            bytes += Files.size(file);
        }
        progress.begin("persisted_checksums", "bytes", bytes);
        for (Path file : files) {
            progress.file(file.getFileName().toString());
            result.put(file.getFileName().toString(), sha256(file, progress));
        }
        return Map.copyOf(result);
    }

    private static void verifyChecksums(Path indexPath, Map<String, String> expected)
            throws IOException {
        if (!Files.isDirectory(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("index artifact is absent or not a directory");
        }
        Map<String, String> actual = checksums(indexPath);
        if (!actual.equals(expected)) {
            throw new IOException("index file set or SHA-256 checksum differs from manifest");
        }
    }

    private static List<Path> indexFiles(Path indexPath) throws IOException {
        try (Stream<Path> entries = Files.list(indexPath)) {
            List<Path> files = entries.sorted(Comparator.comparing(
                            path -> path.getFileName().toString(),
                            SnapshotBuilder.CODE_POINT_ORDER))
                    .toList();
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("index contains a non-regular artifact");
                }
            }
            return files;
        }
    }

    private static String sha256(Path file, BuildProgress progress) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError("SHA-256 is required by Java", error);
        }
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                    progress.advance(count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeDurably(Path file, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            while (source.hasRemaining()) {
                channel.write(source);
            }
            channel.force(true);
        }
    }

    private static void forceTree(Path directory, BuildProgress progress) throws IOException {
        List<Path> files = indexFiles(directory);
        progress.begin("sync_index_files", "files", (long) files.size());
        for (Path file : files) {
            progress.file(file.getFileName().toString());
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            progress.advance(1);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException error) {
            // Directory fsync is unavailable on this platform; Lucene file fsyncs still hold.
        }
    }

    private static void sealTree(Path root) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                root, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> readOnlyFile = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ);
            Set<PosixFilePermission> readOnlyDirectory = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.setPosixFilePermissions(
                            path, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                                    ? readOnlyDirectory
                                    : readOnlyFile);
                }
            }
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    DosFileAttributeView dos = Files.getFileAttributeView(
                            path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                    if (dos != null) {
                        dos.setReadOnly(true);
                    }
                }
            }
        }
    }

    private static void makeTreeWritableForCleanup(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(
                root, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> ownerWritable = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.toList()) {
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        Files.setPosixFilePermissions(path, ownerWritable);
                    }
                }
            }
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                DosFileAttributeView dos = Files.getFileAttributeView(
                        path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (dos != null) {
                    dos.setReadOnly(false);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void closeAfterFailedOpen(
            DirectoryReader reader, Analyzer analyzer, Directory directory, Exception primary) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException error) {
                primary.addSuppressed(error);
            }
        }
        if (analyzer != null) {
            analyzer.close();
        }
        if (directory != null) {
            try {
                directory.close();
            } catch (IOException error) {
                primary.addSuppressed(error);
            }
        }
    }

    private static SnapshotOpenException corrupt(String message, Throwable cause) {
        return cause == null
                ? new SnapshotOpenException(Reason.INTERNAL_ERROR, message)
                : new SnapshotOpenException(Reason.INTERNAL_ERROR, message, cause);
    }
}
