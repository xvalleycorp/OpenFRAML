//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.server;

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
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import vn.io.dxa.openframl.gate.PolicyAdmin.Caller;
import vn.io.dxa.openframl.gate.PolicyAdmin.Role;

/**
 * Who is calling the admin service, and in which roles. The identity is the common name of the client certificate
 * the TLS handshake verified; the roles come from the bank's lists of makers and checkers. A call without a verified
 * certificate is refused before it reaches the service.
 */
public final class AdminAuthentication implements ServerInterceptor {

    private static final System.Logger LOG = System.getLogger(AdminAuthentication.class.getName());

    static final Context.Key<Caller> CALLER = Context.key("openframl-admin-caller");

    private final Set<String> makers;
    private final Set<String> checkers;
    private final Function<ServerCall<?, ?>, Optional<String>> identity;

    /** Takes the identity from the client certificate. */
    public AdminAuthentication(Set<String> makers, Set<String> checkers) {
        this(makers, checkers, AdminAuthentication::certificateName);
    }

    AdminAuthentication(Set<String> makers, Set<String> checkers,
            Function<ServerCall<?, ?>, Optional<String>> identity) {
        this.makers = normalise(makers);
        this.checkers = normalise(checkers);
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
            ServerCallHandler<Q, R> next) {
        Optional<String> name = identity.apply(call);
        if (name.isEmpty()) {
            LOG.log(WARNING, "admin call {0} without a verified client certificate refused",
                    call.getMethodDescriptor().getFullMethodName());
            call.close(Status.UNAUTHENTICATED.withDescription("a client certificate is required"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        String key = name.get().strip().toLowerCase(Locale.ROOT);
        if (makers.contains(key)) {
            roles.add(Role.MAKER);
        }
        if (checkers.contains(key)) {
            roles.add(Role.CHECKER);
        }
        Context context = Context.current().withValue(CALLER, new Caller(name.get(), roles));
        return Contexts.interceptCall(context, call, headers, next);
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
                    return Optional.of(rdn.getValue().toString()).filter(cn -> !cn.isBlank());
                }
            }
            return Optional.empty();
        } catch (SSLPeerUnverifiedException | InvalidNameException e) {
            return Optional.empty();
        }
    }

    private static Set<String> normalise(Set<String> names) {
        return names.stream().map(String::strip).filter(name -> !name.isEmpty())
                .map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
