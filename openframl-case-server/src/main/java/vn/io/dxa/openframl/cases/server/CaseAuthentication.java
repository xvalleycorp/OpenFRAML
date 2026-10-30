//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases.server;

import static java.lang.System.Logger.Level.WARNING;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Who is calling: the common name of the client certificate the TLS handshake verified. A call without one is
 * refused before it reaches the service; what the caller may do is checked there, against the {@link CaseDirectory}.
 */
public final class CaseAuthentication implements ServerInterceptor {

    private static final System.Logger LOG = System.getLogger(CaseAuthentication.class.getName());

    static final Context.Key<String> CALLER = Context.key("openframl-case-caller");

    private final Function<ServerCall<?, ?>, Optional<String>> identity;

    /** Takes the identity from the client certificate. */
    public CaseAuthentication() {
        this(CaseAuthentication::certificateName);
    }

    CaseAuthentication(Function<ServerCall<?, ?>, Optional<String>> identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
            ServerCallHandler<Q, R> next) {
        Optional<String> name = identity.apply(call).filter(n -> !n.isBlank());
        if (name.isEmpty()) {
            LOG.log(WARNING, "case call {0} without a verified client certificate refused",
                    call.getMethodDescriptor().getFullMethodName());
            call.close(Status.UNAUTHENTICATED.withDescription("a client certificate is required"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return Contexts.interceptCall(Context.current().withValue(CALLER, name.get().strip()), call, headers, next);
    }

    /** The common name of the verified client certificate. */
    static Optional<String> certificateName(ServerCall<?, ?> call) {
        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (session == null) {
            return Optional.empty();
        }
        try {
            Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate certificate)) {
                return Optional.empty();
            }
            for (Rdn rdn : new LdapName(certificate.getSubjectX500Principal().getName()).getRdns()) {
                if (rdn.getType().equalsIgnoreCase("CN")) {
                    return Optional.of(rdn.getValue().toString());
                }
            }
            return Optional.empty();
        } catch (SSLPeerUnverifiedException | InvalidNameException e) {
            return Optional.empty();
        }
    }
}
