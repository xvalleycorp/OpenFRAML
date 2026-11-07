//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Serves {@code GET /metrics} in the Prometheus text format, with the JDK's own HTTP server, so that no metrics
 * library is needed. The metrics are counts and times only: never an account, a customer or a name.
 */
public final class MetricsServer implements AutoCloseable {

    private final HttpServer server;

    private MetricsServer(HttpServer server) {
        this.server = server;
    }

    /**
     * @param port    the port, or 0 for any free one
     * @param metrics the metrics as Prometheus text, read on each request
     */
    public static MetricsServer start(int port, Supplier<String> metrics) throws IOException {
        Objects.requireNonNull(metrics, "metrics");
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", exchange -> respond(exchange, metrics));
        server.setExecutor(null);
        server.start();
        return new MetricsServer(server);
    }

    private static void respond(HttpExchange exchange, Supplier<String> metrics) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = metrics.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
