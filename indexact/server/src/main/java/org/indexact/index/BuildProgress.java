package org.indexact.index;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.indexact.protocol.json.CompactWireJson;

/** Operational diagnostics only; never part of snapshot identity, manifests or model input. */
final class BuildProgress implements AutoCloseable {
    static final BuildProgress NONE = new BuildProgress(null, Duration.ZERO);

    private final PrintStream output;
    private final ScheduledExecutorService timer;
    private final long started = System.nanoTime();
    private final AtomicLong completed = new AtomicLong();
    private volatile boolean disabled;
    private String stage;
    private String unit;
    private Long total;
    private String currentFile;
    private long stageStarted;
    private long sequence;
    private boolean terminal;

    BuildProgress(PrintStream output, Duration interval) {
        if (interval.isNegative() || (output != null && interval.isZero())) {
            throw new IllegalArgumentException("progress interval must be positive");
        }
        this.output = output;
        disabled = output == null;
        if (disabled) {
            timer = null;
        } else {
            timer = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "snapshot-build-progress");
                thread.setDaemon(true);
                return thread;
            });
            timer.scheduleWithFixedDelay(this::heartbeat, interval.toNanos(),
                    interval.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    synchronized void begin(String nextStage, String nextUnit, Long nextTotal) {
        if (disabled || terminal) {
            return;
        }
        if (stage != null) {
            emit("stage_finished", null);
        }
        stage = nextStage;
        unit = nextUnit;
        total = nextTotal;
        currentFile = null;
        completed.set(0);
        stageStarted = System.nanoTime();
        emit("stage_started", null);
    }

    void advance(long amount) {
        if (!disabled) {
            completed.addAndGet(amount);
        }
    }

    synchronized void file(String name) {
        if (!disabled) {
            currentFile = name;
        }
    }

    synchronized void heartbeat() {
        if (!disabled && !terminal && stage != null) {
            emit("heartbeat", null);
        }
    }

    synchronized void succeeded(String snapshotId) {
        if (!disabled && !terminal) {
            emit("stage_finished", null);
            terminal = true;
            emit("completed", snapshotId);
        }
    }

    synchronized void failed(BuildError.Reason reason) {
        if (!disabled && !terminal) {
            terminal = true;
            emit("failed", reason.name());
        }
    }

    private void emit(String event, String detail) {
        long now = System.nanoTime();
        long count = completed.get();
        double stageSeconds = (now - stageStarted) / 1_000_000_000.0;
        var data = new LinkedHashMap<String, Object>();
        data.put("type", "snapshot_build_progress");
        data.put("pid", ProcessHandle.current().pid());
        data.put("sequence", ++sequence);
        data.put("timestamp_utc", Instant.now().toString());
        data.put("event", event);
        data.put("stage", stage);
        data.put("elapsed_seconds", (now - started) / 1_000_000_000.0);
        data.put("stage_elapsed_seconds", stageSeconds);
        data.put("unit", unit);
        data.put("completed_units", count);
        data.put("total_units", total);
        // A percentage of documents/bytes is NOT a percentage of total build time.
        data.put("stage_percent", total == null || total == 0 ? null : 100.0 * count / total);
        data.put("units_per_second", unit == null || stageSeconds == 0 ? null : count / stageSeconds);
        data.put("current_file", currentFile);
        if (event.equals("completed")) {
            data.put("snapshot_id", detail);
        } else if (event.equals("failed")) {
            data.put("failure_reason", detail);
        }
        try {
            output.println(new String(CompactWireJson.encode(data), StandardCharsets.UTF_8));
            output.flush();
            if (output.checkError()) {
                disabled = true;
            }
        } catch (RuntimeException ignored) {
            // A broken diagnostics stream must not change publication or validation semantics.
            disabled = true;
        }
    }

    @Override
    public synchronized void close() {
        terminal = true;
        if (timer != null) {
            timer.shutdownNow();
        }
        // stderr belongs to the caller. Closing without succeeded() never reports success.
    }
}
