//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases.server;

import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import vn.io.dxa.openframl.cases.CaseService;
import vn.io.dxa.openframl.cases.CaseStore;
import vn.io.dxa.openframl.cases.jdbc.JdbcCaseStore;
import vn.io.dxa.openframl.cases.memory.InMemoryCaseStore;

/**
 * Runs Case Management as a gRPC server, mutual TLS only.
 *
 * <p>Configured from the environment:
 * <ul>
 *   <li>{@code OPENFRAML_CASE_PORT}: the port, default {@code 9190};</li>
 *   <li>{@code OPENFRAML_CASE_TLS_CERT} and {@code OPENFRAML_CASE_TLS_KEY}: the server's certificate chain and PKCS#8
 *       key, PEM; {@code OPENFRAML_CASE_TLS_CLIENT_CA}: the CA that issues client certificates; all required;</li>
 *   <li>{@code OPENFRAML_CASE_ANALYSTS}, {@code OPENFRAML_CASE_SUPERVISORS}, {@code OPENFRAML_CASE_DETECTORS}:
 *       certificate common names, comma-separated;</li>
 *   <li>{@code OPENFRAML_CASE_JDBC_URL}: the case database, e.g.
 *       {@code jdbc:postgresql://db/openframl?user=..&password=..}, with its driver on the class path; without it,
 *       cases are kept in memory, for trials only.</li>
 * </ul>
 */
public final class CaseServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(CaseServer.class.getName());

    private final Server server;

    private CaseServer(Server server) {
        this.server = server;
    }

    /** Starts serving on {@code port}; port 0 picks a free one. {@code credentials} must require a client certificate. */
    public static CaseServer start(int port, ServerCredentials credentials, CaseGrpcService service,
            CaseAuthentication authentication) throws IOException {
        return new CaseServer(Grpc.newServerBuilderForPort(port, credentials)
                .addService(ServerInterceptors.intercept(service, authentication)).build().start());
    }

    /** TLS that requires, and verifies against {@code clientCa}, a client certificate. */
    public static ServerCredentials mutualTls(File certChain, File privateKey, File clientCa) throws IOException {
        return TlsServerCredentials.newBuilder().keyManager(certChain, privateKey).trustManager(clientCa)
                .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE).build();
    }

    public int port() {
        return server.getPort();
    }

    /** Stops accepting calls and waits up to 10 seconds for the calls in flight. */
    @Override
    public void close() throws InterruptedException {
        server.shutdown();
        if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
            server.shutdownNow();
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<String, String> env = System.getenv();
        String url = env.get("OPENFRAML_CASE_JDBC_URL");
        CaseStore store;
        if (url == null) {
            LOG.log(System.Logger.Level.WARNING, "OPENFRAML_CASE_JDBC_URL not set: cases are kept in memory, for "
                    + "trials only");
            store = new InMemoryCaseStore();
        } else {
            store = new JdbcCaseStore(new DriverDataSource(url));
        }
        CaseDirectory directory = new CaseDirectory(names(env.get("OPENFRAML_CASE_ANALYSTS")),
                names(env.get("OPENFRAML_CASE_SUPERVISORS")), names(env.get("OPENFRAML_CASE_DETECTORS")));
        CaseServer server = start(Integer.parseInt(env.getOrDefault("OPENFRAML_CASE_PORT", "9190")),
                mutualTls(required(env, "OPENFRAML_CASE_TLS_CERT"), required(env, "OPENFRAML_CASE_TLS_KEY"),
                        required(env, "OPENFRAML_CASE_TLS_CLIENT_CA")),
                new CaseGrpcService(new CaseService(store, Clock.systemUTC()), directory), new CaseAuthentication());
        LOG.log(System.Logger.Level.INFO, "case service listening on port {0} (mutual TLS)", server.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        server.server.awaitTermination();
    }

    private static File required(Map<String, String> env, String name) {
        String path = env.get(name);
        if (path == null) {
            throw new IllegalStateException(name + " is required");
        }
        return new File(path);
    }

    private static Set<String> names(String list) {
        return list == null ? Set.of() : Arrays.stream(list.split(",")).map(String::strip)
                .filter(name -> !name.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Connections from {@link DriverManager}, one per call: enough for the volume of a case service. A bank that
     * wants a pool builds its own {@link JdbcCaseStore} with its pool and serves it with {@link #start}.
     */
    private record DriverDataSource(String url) implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }
}
