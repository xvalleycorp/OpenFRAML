//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.server;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/** Tests only: takes the caller's name from a header, where production takes it from the client certificate. */
final class TestCaller implements ServerInterceptor {

    static final Context.Key<String> NAME = Context.key("test-caller");

    private final Metadata.Key<String> header;

    TestCaller(Metadata.Key<String> header) {
        this.header = header;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
            ServerCallHandler<Q, R> next) {
        return Contexts.interceptCall(Context.current().withValue(NAME, headers.get(header)), call, headers, next);
    }
}
