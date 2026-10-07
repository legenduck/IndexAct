package org.indexact.protocol.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.indexact.execution.Bm25Parameters;
import org.indexact.index.Snapshot;
import org.indexact.index.SnapshotBuilder;
import org.indexact.index.SnapshotStore;
import org.indexact.protocol.ProtocolService;
import org.indexact.protocol.json.CompactWireJson;
import org.indexact.protocol.json.StrictJsonParser;

/** Noninteractive launcher for a durable snapshot or a conformance JSONL corpus. */
public final class ServerMain {
    record Configuration(
            Path corpus, Path store, String snapshotId, String host, int port, String path,
            Bm25Parameters bm25) {}

    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        Configuration configuration = parseArguments(args);
        Snapshot snapshot = loadSnapshot(configuration);
        ProtocolService protocol = new ProtocolService(List.of(snapshot), configuration.bm25());
        IndexActHttpServer server = new IndexActHttpServer(
                new InetSocketAddress(configuration.host(), configuration.port()),
                configuration.path(),
                protocol);
        AtomicBoolean closed = new AtomicBoolean();
        CountDownLatch stopped = new CountDownLatch(1);
        Runnable shutdown = () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                server.close();
            } finally {
                try {
                    snapshot.close();
                } catch (IOException ignored) {
                    // The process is terminating; no protocol response exists for shutdown I/O.
                } finally {
                    stopped.countDown();
                }
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "indexact-shutdown"));
        try {
            server.start();
            System.err.printf("BM25: k1=%s, b=%s%n",
                    configuration.bm25().k1(), configuration.bm25().b());

            LinkedHashMap<String, Object> handshake = new LinkedHashMap<>();
            handshake.put(
                    "endpoint",
                    "http://" + configuration.host() + ":" + server.address().getPort()
                            + configuration.path());
            handshake.put("snapshot_id", snapshot.snapshotId());
            System.out.write(CompactWireJson.encode(handshake));
            System.out.write('\n');
            System.out.flush();

            // Service lifetime follows process shutdown, not the caller's stdin.
            stopped.await();
        } finally {
            shutdown.run();
        }
    }

    static Snapshot loadSnapshot(Configuration configuration) throws Exception {
        if (configuration.corpus() != null) {
            List<SnapshotBuilder.InputDocument> documents = readCorpus(configuration.corpus());
            return new SnapshotBuilder().build(documents);
        }
        return new SnapshotStore(configuration.store()).open(configuration.snapshotId());
    }

    static Configuration parseArguments(String[] args) {
        Path corpus = null;
        Path store = null;
        String snapshotId = null;
        String host = "127.0.0.1";
        int port = 0;
        String path = "/";
        Bm25Parameters bm25 = Bm25Parameters.WIKIPEDIA_18;
        if (args.length > 0 && !args[0].startsWith("--")) {
            corpus = Path.of(args[0]);
            if (args.length > 1) {
                host = args[1];
            }
            if (args.length > 2) {
                port = parsePort(args[2]);
            }
            if (args.length > 3) {
                path = args[3];
            }
            if (args.length > 4) {
                throw usage();
            }
        } else {
            for (int index = 0; index < args.length; index += 2) {
                if (index + 1 >= args.length) {
                    throw usage();
                }
                switch (args[index]) {
                    case "--corpus" -> corpus = Path.of(args[index + 1]);
                    case "--store" -> store = Path.of(args[index + 1]);
                    case "--snapshot-id" -> snapshotId = args[index + 1];
                    case "--host" -> host = args[index + 1];
                    case "--port" -> port = parsePort(args[index + 1]);
                    case "--path" -> path = args[index + 1];
                    case "--dataset" -> bm25 = Bm25Parameters.forDataset(args[index + 1]);
                    default -> throw usage();
                }
            }
        }
        boolean corpusMode = corpus != null && store == null && snapshotId == null;
        boolean storeMode = corpus == null && store != null && snapshotId != null;
        if ((!corpusMode && !storeMode) || host.isEmpty() || !path.startsWith("/")) {
            throw usage();
        }
        return new Configuration(corpus, store, snapshotId, host, port, path, bm25);
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 0 || port > 65_535) {
                throw usage();
            }
            return port;
        } catch (NumberFormatException error) {
            throw usage();
        }
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "usage: ServerMain CORPUS_JSONL [HOST [PORT [PATH]]] or "
                        + "(--corpus FILE | --store DIR --snapshot-id SNAPSHOT_ID) "
                        + "[--host HOST] [--port PORT] [--path PATH] "
                        + "[--dataset bcplus|wikipedia-18]");
    }

    @SuppressWarnings("unchecked")
    private static List<SnapshotBuilder.InputDocument> readCorpus(Path corpus) throws Exception {
        ArrayList<SnapshotBuilder.InputDocument> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(corpus, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                final Object parsed;
                try {
                    parsed = StrictJsonParser.parse(line.getBytes(StandardCharsets.UTF_8));
                } catch (Exception error) {
                    throw new IOException("invalid corpus JSON at line " + lineNumber, error);
                }
                if (!(parsed instanceof Map<?, ?> map)
                        || !map.keySet().equals(java.util.Set.of("doc_key", "raw_text"))
                        || !(map.get("doc_key") instanceof String docKey)
                        || !(map.get("raw_text") instanceof String rawText)) {
                    throw new IOException(
                            "corpus line " + lineNumber
                                    + " must contain exactly string doc_key and raw_text fields");
                }
                result.add(new SnapshotBuilder.InputDocument(docKey, rawText));
            }
        }
        return List.copyOf(result);
    }
}
