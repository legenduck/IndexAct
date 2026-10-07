package org.indexact.index;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import org.indexact.protocol.json.CompactWireJson;

/**
 * Noninteractive snapshot builder.
 *
 * <p>Invocation: {@code SnapshotBuildCli --input CORPUS.jsonl --store SNAPSHOT_DIR
 * [--flush-every DOCUMENTS] [--build-mode memory|speed] [--workers N]
 * [--ram-buffer-mb MB] [--max-in-flight-mb MB] [--progress-interval-seconds N]}.
 * Each input line must be one exact JSON object with string fields
 * {@code doc_key} and {@code raw_text}. Success writes one compact JSON object containing the
 * deterministic {@code snapshot_id} to stdout. Diagnostics go to stderr and exit status is nonzero.
 */
public final class SnapshotBuildCli {
    private static final String USAGE = "Usage: SnapshotBuildCli --input CORPUS.jsonl "
            + "--store SNAPSHOT_DIR [--flush-every DOCUMENTS] "
            + "[--build-mode memory|speed] [--workers N] "
            + "[--ram-buffer-mb MB] [--max-in-flight-mb MB] "
            + "[--progress-interval-seconds N (default 5; 0 disables)]";

    private SnapshotBuildCli() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    public static int run(String[] args, PrintStream output, PrintStream error) {
        Path input = null;
        Path store = null;
        Integer flushEvery = null;
        SnapshotBuilder.BuildMode mode = null;
        Integer workers = null;
        Integer ramBufferMB = null;
        Integer maxInFlightMB = null;
        Integer progressInterval = null;
        SnapshotBuilder.Options options;
        try {
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (argument.equals("--help")) {
                    if (args.length != 1) {
                        throw new IllegalArgumentException("--help cannot be combined with options");
                    }
                    output.println(USAGE);
                    return 0;
                }
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + argument);
                }
                String value = args[++index];
                switch (argument) {
                    case "--input" -> {
                        if (input != null) {
                            throw new IllegalArgumentException("duplicate --input");
                        }
                        input = Path.of(value);
                    }
                    case "--store" -> {
                        if (store != null) {
                            throw new IllegalArgumentException("duplicate --store");
                        }
                        store = Path.of(value);
                    }
                    case "--flush-every" -> {
                        if (flushEvery != null) {
                            throw new IllegalArgumentException("duplicate --flush-every");
                        }
                        flushEvery = parseNonNegativeInt(value);
                    }
                    case "--build-mode" -> {
                        if (mode != null) {
                            throw new IllegalArgumentException("duplicate --build-mode");
                        }
                        mode = switch (value) {
                            case "memory" -> SnapshotBuilder.BuildMode.MEMORY;
                            case "speed" -> SnapshotBuilder.BuildMode.SPEED;
                            default -> throw new IllegalArgumentException(
                                    "--build-mode must be memory or speed");
                        };
                    }
                    case "--workers" -> {
                        if (workers != null) {
                            throw new IllegalArgumentException("duplicate --workers");
                        }
                        workers = parseNonNegativeInt(value, "--workers");
                    }
                    case "--ram-buffer-mb" -> {
                        if (ramBufferMB != null) {
                            throw new IllegalArgumentException("duplicate --ram-buffer-mb");
                        }
                        ramBufferMB = parseNonNegativeInt(value, argument);
                    }
                    case "--max-in-flight-mb" -> {
                        if (maxInFlightMB != null) {
                            throw new IllegalArgumentException("duplicate --max-in-flight-mb");
                        }
                        maxInFlightMB = parseNonNegativeInt(value, argument);
                    }
                    case "--progress-interval-seconds" -> {
                        if (progressInterval != null) {
                            throw new IllegalArgumentException("duplicate --progress-interval-seconds");
                        }
                        progressInterval = parseNonNegativeInt(value, argument);
                    }
                    default -> throw new IllegalArgumentException("unknown option " + argument);
                }
            }
            if (input == null || store == null) {
                throw new IllegalArgumentException("--input and --store are required");
            }
            if (mode == null) {
                mode = SnapshotBuilder.BuildMode.MEMORY;
            }
            int effectiveWorkers = workers == null
                    ? (mode == SnapshotBuilder.BuildMode.SPEED
                            ? Runtime.getRuntime().availableProcessors() : 1)
                    : workers;
            options = new SnapshotBuilder.Options(flushEvery == null ? 0 : flushEvery,
                    mode, effectiveWorkers);
            if (ramBufferMB != null || maxInFlightMB != null) {
                if (mode != SnapshotBuilder.BuildMode.SPEED) {
                    throw new IllegalArgumentException("buffer tuning requires speed build mode");
                }
                options = new SnapshotBuilder.Options(options.flushEveryDocuments(), mode, effectiveWorkers,
                        ramBufferMB == null ? options.ramBufferMB() : ramBufferMB,
                        maxInFlightMB == null ? options.maxInFlightMB() : maxInFlightMB);
            }
        } catch (IllegalArgumentException exception) {
            error.println(exception.getMessage());
            error.println(USAGE);
            return 2;
        }

        int interval = progressInterval == null ? 5 : progressInterval;
        try (BuildProgress progress = interval == 0 ? BuildProgress.NONE
                : new BuildProgress(error, Duration.ofSeconds(interval))) {
            SnapshotStore snapshots = new SnapshotStore(store);
            String snapshotId = snapshots.buildJsonLines(input, options, progress);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("snapshot_id", snapshotId);
            output.println(new String(
                    CompactWireJson.encode(result), java.nio.charset.StandardCharsets.UTF_8));
            return 0;
        } catch (BuildError exception) {
            error.println("snapshot build failed [" + exception.reason() + "]: "
                    + exception.getMessage());
            return 1;
        }
    }

    private static int parseNonNegativeInt(String value) {
        return parseNonNegativeInt(value, "--flush-every");
    }

    private static int parseNonNegativeInt(String value, String option) {
        if (value.isEmpty() || !value.chars().allMatch(character -> character >= '0' && character <= '9')) {
            throw new IllegalArgumentException(option + " must be a non-negative decimal integer");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(option + " exceeds the supported integer range");
        }
    }
}
