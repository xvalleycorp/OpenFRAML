//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.server;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import vn.io.dxa.openframl.gate.Gate;
import vn.io.dxa.openframl.gate.v1.DecideRequest;
import vn.io.dxa.openframl.gate.v1.DecideResponse;
import vn.io.dxa.openframl.gate.v1.GateServiceGrpc;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * {@code openframl.gate.v1.GateService} on top of a {@link Gate}. The caller's deadline, less a margin for the reply
 * to travel back, is the gate's time budget; a call without a deadline gets the default budget.
 */
public final class GateGrpcService extends GateServiceGrpc.GateServiceImplBase {

    private final Gate gate;
    private final Duration defaultBudget;
    private final long replyMarginNanos;

    /**
     * @param defaultBudget the budget of a call without a deadline
     * @param replyMargin   time kept back from the caller's deadline to send the reply
     */
    public GateGrpcService(Gate gate, Duration defaultBudget, Duration replyMargin) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.defaultBudget = Objects.requireNonNull(defaultBudget, "defaultBudget");
        this.replyMarginNanos = replyMargin.toNanos();
    }

    @Override
    public void decide(DecideRequest request, StreamObserver<DecideResponse> responses) {
        TransactionEvent event;
        try {
            event = ProtoMapping.event(request.getTransaction());
        } catch (IllegalArgumentException | NullPointerException e) {
            responses.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        responses.onNext(ProtoMapping.response(gate.decide(event, budget(Context.current().getDeadline()))));
        responses.onCompleted();
    }

    private Duration budget(Deadline deadline) {
        if (deadline == null) {
            return defaultBudget;
        }
        return Duration.ofNanos(Math.max(0, deadline.timeRemaining(TimeUnit.NANOSECONDS) - replyMarginNanos));
    }
}
