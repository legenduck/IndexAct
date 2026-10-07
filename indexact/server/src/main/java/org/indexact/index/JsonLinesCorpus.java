package org.indexact.index;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.indexact.protocol.json.JsonParseException;
import org.indexact.protocol.json.StrictJsonParser;

/** Strict UTF-8 JSONL reader for exact {@code (doc_key, raw_text)} build records. */
public final class JsonLinesCorpus {
    private static final Set<String> FIELDS = Set.of("doc_key", "raw_text");

    private JsonLinesCorpus() {}

    public static List<SnapshotBuilder.InputDocument> read(Path input) throws BuildError {
        List<SnapshotBuilder.InputDocument> documents = new ArrayList<>();
        forEach(input, documents::add);
        return List.copyOf(documents);
    }

    /** Visits one record at a time; the caller must not retain document bodies. */
    static void forEach(Path input, SnapshotBuilder.DocumentConsumer consumer) throws BuildError {
        lines(input, (line, number) -> consumer.accept(parse(line, number)));
    }

    /** Parsing is deferred to the indexing worker, not performed by the input producer. */
    static SnapshotBuilder.DocumentSource source(Path input) {
        return new SnapshotBuilder.DocumentSource() {
            @Override
            public void forEach(SnapshotBuilder.DocumentConsumer consumer) throws BuildError {
                JsonLinesCorpus.forEach(input, consumer);
            }

            @Override
            public void forEachDeferred(SnapshotBuilder.DeferredConsumer consumer) throws BuildError {
                bufferedLines(input, (line, number) ->
                        consumer.accept(() -> parse(line, number), line.length));
            }
        };
    }

    private record LineCheck(long number, BuildError failure) {}

    static void copyValidated(Path input, Path copy, SnapshotBuilder.Options options) throws BuildError {
        copyValidated(input, copy, options, BuildProgress.NONE);
    }

    static long copyValidated(Path input, Path copy, SnapshotBuilder.Options options,
            BuildProgress progress) throws BuildError {
        if (options.mode() == SnapshotBuilder.BuildMode.MEMORY) {
            return copyValidated(input, copy, progress);
        }
        LineCheck[] firstFailure = {null};
        long[] validated = {0};
        try (var output = new BufferedOutputStream(Files.newOutputStream(copy), 1024 * 1024);
                var pipeline = new ParallelBuildPipeline<LineCheck>(options.workers(),
                        options.maxInFlightBytes(), result -> {
                            if (result.failure() == null) {
                                validated[0]++;
                                progress.advance(1);
                            }
                            if (result.failure() != null && (firstFailure[0] == null
                                    || result.number() < firstFailure[0].number())) {
                                firstFailure[0] = result;
                            }
                        })) {
            try {
                bufferedLines(input, (line, number) -> {
                    pipeline.submit(line.length, () -> {
                        try {
                            parse(line, number);
                            return new LineCheck(number, null);
                        } catch (BuildError failure) {
                            return new LineCheck(number, failure);
                        }
                    });
                    output.write(line);
                    output.write('\n');
                });
            } catch (BuildError failure) {
                // A later read/write failure must not hide a previously read invalid JSON row.
                pipeline.drain();
                if (firstFailure[0] != null) {
                    firstFailure[0].failure().addSuppressed(failure);
                    throw firstFailure[0].failure();
                }
                throw failure;
            }
            pipeline.drain();
            if (firstFailure[0] != null) {
                throw firstFailure[0].failure();
            }
        } catch (IOException failure) {
            throw new BuildError(BuildError.Reason.INDEX_IO_FAILURE,
                    "could not spool JSONL build input", failure);
        }
        return validated[0];
    }

    /**
     * Freeze and validate the input before semantic validation/indexing. Keeping a private disk
     * copy preserves JSON-error precedence and prevents a changed source from changing the build.
     */
    static void copyValidated(Path input, Path copy) throws BuildError {
        copyValidated(input, copy, BuildProgress.NONE);
    }

    private static long copyValidated(Path input, Path copy, BuildProgress progress) throws BuildError {
        long[] validated = {0};
        try (var output = new BufferedOutputStream(Files.newOutputStream(copy))) {
            lines(input, (line, number) -> {
                parse(line, number);
                output.write(line);
                output.write('\n');
                validated[0]++;
                progress.advance(1);
            });
        } catch (IOException error) {
            throw new BuildError(
                    BuildError.Reason.INDEX_IO_FAILURE, "could not spool JSONL build input", error);
        }
        return validated[0];
    }

    @FunctionalInterface
    private interface LineConsumer {
        void accept(byte[] line, long number) throws BuildError, IOException;
    }

    /** Speed-mode producer: scan in-memory blocks rather than read()/write() per byte. */
    private static void bufferedLines(Path input, LineConsumer consumer) throws BuildError {
        try (InputStream stream = Files.newInputStream(input)) {
            byte[] buffer = new byte[1024 * 1024];
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            long number = 1;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                int start = 0;
                for (int index = 0; index < count; index++) {
                    if (buffer[index] == '\n') {
                        line.write(buffer, start, index - start);
                        consumer.accept(line.toByteArray(), number++);
                        line.reset();
                        start = index + 1;
                    }
                }
                line.write(buffer, start, count - start);
            }
            if (line.size() > 0) {
                consumer.accept(line.toByteArray(), number);
            }
        } catch (IOException failure) {
            throw new BuildError(BuildError.Reason.INDEX_IO_FAILURE,
                    "could not read JSONL build input", failure);
        }
    }

    private static void lines(Path input, LineConsumer consumer) throws BuildError {
        try (InputStream stream = new BufferedInputStream(Files.newInputStream(input))) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            long lineNumber = 1;
            int next;
            while ((next = stream.read()) >= 0) {
                if (next == '\n') {
                    consumer.accept(line.toByteArray(), lineNumber);
                    line.reset();
                    lineNumber++;
                } else {
                    line.write(next);
                }
            }
            if (line.size() > 0) {
                consumer.accept(line.toByteArray(), lineNumber);
            }
        } catch (BuildError error) {
            throw error;
        } catch (IOException error) {
            throw new BuildError(
                    BuildError.Reason.INDEX_IO_FAILURE, "could not read JSONL build input", error);
        }
    }

    private static SnapshotBuilder.InputDocument parse(byte[] line, long lineNumber)
            throws BuildError {
        final Object value;
        try {
            value = StrictJsonParser.parse(line);
        } catch (JsonParseException error) {
            throw invalid(lineNumber, "record is not strict UTF-8 JSON", error);
        }
        if (!(value instanceof Map<?, ?> object) || !object.keySet().equals(FIELDS)) {
            throw invalid(lineNumber, "record must contain exactly doc_key and raw_text", null);
        }
        Object docKey = object.get("doc_key");
        Object rawText = object.get("raw_text");
        if (!(docKey instanceof String key) || !(rawText instanceof String raw)) {
            throw invalid(lineNumber, "doc_key and raw_text must be strings", null);
        }
        return new SnapshotBuilder.InputDocument(key, raw);
    }

    private static BuildError invalid(long lineNumber, String message, Exception cause) {
        String bounded = "invalid JSONL record at line " + lineNumber + ": " + message;
        return cause == null
                ? new BuildError(BuildError.Reason.INVALID_INPUT_RECORD, bounded)
                : new BuildError(BuildError.Reason.INVALID_INPUT_RECORD, bounded, cause);
    }
}
