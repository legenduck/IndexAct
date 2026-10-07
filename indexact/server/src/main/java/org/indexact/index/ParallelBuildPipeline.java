package org.indexact.index;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Completion-driven work queue: a slow early document never gates later submissions. */
final class ParallelBuildPipeline<T> implements AutoCloseable {
    @FunctionalInterface
    interface ResultConsumer<T> {
        void accept(T result) throws BuildError, IOException;
    }

    private final ExecutorService executor;
    private final ExecutorCompletionService<T> completed;
    private final Map<Future<T>, Long> pending = new IdentityHashMap<>();
    private final ResultConsumer<T> consumer;
    private final long byteLimit;
    private final int taskLimit;
    private long retainedInputBytes;

    ParallelBuildPipeline(int workers, long byteLimit, ResultConsumer<T> consumer) {
        this(workers, byteLimit,
                (int) Math.min(Integer.MAX_VALUE, Math.max(64L, 64L * workers)), consumer);
    }

    ParallelBuildPipeline(int workers, long byteLimit, int taskLimit, ResultConsumer<T> consumer) {
        if (workers < 1 || byteLimit < 1 || taskLimit < workers) {
            throw new IllegalArgumentException("positive workers/bytes and taskLimit >= workers required");
        }
        this.byteLimit = byteLimit;
        this.taskLimit = taskLimit;
        this.consumer = consumer;
        executor = Executors.newFixedThreadPool(workers,
                Thread.ofPlatform().name("snapshot-build-worker-", 0).factory());
        completed = new ExecutorCompletionService<>(executor);
    }

    void submit(long inputBytes, Callable<T> work) throws BuildError, IOException {
        if (inputBytes < 0) {
            throw new IllegalArgumentException("negative retained input size");
        }
        Future<T> ready;
        while ((ready = completed.poll()) != null) {
            accept(ready);
        }
        // One oversized record is admitted alone; a queue budget never truncates/rejects input.
        while (!pending.isEmpty() && (pending.size() >= taskLimit
                || inputBytes > byteLimit - retainedInputBytes)) {
            awaitAny();
        }
        Future<T> future = completed.submit(work);
        pending.put(future, inputBytes);
        retainedInputBytes += inputBytes;
    }

    void drain() throws BuildError, IOException {
        while (!pending.isEmpty()) {
            awaitAny();
        }
    }

    private void awaitAny() throws BuildError, IOException {
        try {
            accept(completed.take());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("snapshot build interrupted", error);
        }
    }

    private void accept(Future<T> ready) throws BuildError, IOException {
        retainedInputBytes -= pending.remove(ready);
        try {
            consumer.accept(ready.get());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("snapshot build interrupted", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof BuildError failure) {
                throw failure;
            }
            if (cause instanceof IOException failure) {
                throw failure;
            }
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            if (cause instanceof Error failure) {
                throw failure;
            }
            throw new IOException("snapshot worker failed", cause);
        }
    }

    @Override
    public void close() {
        for (Future<T> future : pending.keySet()) {
            future.cancel(true);
        }
        executor.shutdownNow();
        // Wait for jobs to release their writer/analyzer resources before index rollback.
        executor.close();
        pending.clear();
        retainedInputBytes = 0;
    }
}
