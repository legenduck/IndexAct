package org.indexact.protocol.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.indexact.protocol.ProtocolService;

/** Small HTTP/1.1 adapter that leaves all JSON/protocol semantics to {@link ProtocolService}. */
public final class IndexActHttpServer implements AutoCloseable {
    private static final String REQUEST_TOKEN = "X-IndexAct-Request-Token";
    private static final String CANCEL_TOKEN = "X-IndexAct-Cancel-Token";
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");
    private static final byte[] CANCEL_RESPONSE =
            "{\"cancelled\":true}".getBytes(StandardCharsets.UTF_8);

    private final com.sun.net.httpserver.HttpServer server;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Thread> activeRequests = new ConcurrentHashMap<>();

    public IndexActHttpServer(
            InetSocketAddress address, String path, ProtocolService protocol) throws IOException {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(protocol, "protocol");
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("HTTP context path must start with '/'");
        }
        server = com.sun.net.httpserver.HttpServer.create(address, 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(path, new Handler(protocol, activeRequests));
    }

    public void start() {
        server.start();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    int activeRequestCount() {
        return activeRequests.size();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private static final class Handler implements HttpHandler {
        private final ProtocolService protocol;
        private final ConcurrentHashMap<String, Thread> activeRequests;

        private Handler(
                ProtocolService protocol, ConcurrentHashMap<String, Thread> activeRequests) {
            this.protocol = protocol;
            this.activeRequests = activeRequests;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] response;
            try {
                if (!exchange.getRequestMethod().equals("POST")) {
                    response = protocol.malformedTransportRequest("HTTP method must be POST");
                } else if (exchange.getRequestHeaders().getFirst(CANCEL_TOKEN) != null) {
                    response = cancel(exchange.getRequestHeaders().getFirst(CANCEL_TOKEN));
                } else {
                    String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
                    if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
                        response = protocol.malformedTransportRequest(
                                "Content-Encoding must be identity");
                    } else {
                        Body body = receive(
                                exchange.getRequestBody(), protocol.serviceLimits().maxRequestBytes());
                        if (body.overLimit()) {
                            response = protocol.requestTooLarge(body.observedBytes());
                        } else {
                            response = execute(
                                    exchange.getRequestHeaders().getFirst(REQUEST_TOKEN),
                                    body.bytes());
                        }
                    }
                }
            } catch (RuntimeException error) {
                response = protocol.malformedTransportRequest("malformed HTTP request");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Content-Encoding", "identity");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            } finally {
                exchange.close();
            }
        }

        private byte[] execute(String token, byte[] body) {
            if (token == null) {
                return protocol.handle(body);
            }
            if (!TOKEN.matcher(token).matches()) {
                return protocol.malformedTransportRequest("invalid request cancellation token");
            }
            Thread current = Thread.currentThread();
            if (activeRequests.putIfAbsent(token, current) != null) {
                return protocol.malformedTransportRequest("duplicate active request token");
            }
            try {
                return protocol.handle(body);
            } finally {
                activeRequests.remove(token, current);
            }
        }

        private byte[] cancel(String token) {
            if (!TOKEN.matcher(token).matches()) {
                return protocol.malformedTransportRequest("invalid request cancellation token");
            }
            Thread active = activeRequests.get(token);
            if (active != null) {
                active.interrupt();
            }
            return CANCEL_RESPONSE;
        }

        private record Body(byte[] bytes, boolean overLimit, long observedBytes) {}

        private static Body receive(InputStream input, long limit) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    (int) Math.min(Math.min(limit, 8192), Integer.MAX_VALUE));
            byte[] buffer = new byte[8192];
            long received = 0;
            while (true) {
                int count = input.read(buffer);
                if (count < 0) {
                    return new Body(output.toByteArray(), false, received);
                }
                received = Math.addExact(received, count);
                if (received > limit) {
                    return new Body(new byte[0], true, received);
                }
                output.write(buffer, 0, count);
            }
        }
    }
}
