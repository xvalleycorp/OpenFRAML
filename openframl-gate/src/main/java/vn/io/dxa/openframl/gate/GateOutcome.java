//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.openframl.model.Decision;

/**
 * The gate's answer to the payment hub.
 *
 * <p>Reason codes are for the bank's staff and systems. They must not be shown to the customer: AML reasons are
 * confidential.
 *
 * @param decision      what to do with the payment
 * @param reasonCodes   why, from every rule that fired, in table order
 * @param policyVersion the decision table version that decided, e.g. {@code gate_decision@1@sha256:...}
 * @param fallback      set when the fallback policy decided instead of the decision table
 * @param reviewLater   the payment was let through without a full decision, and monitoring should look at it again
 */
public record GateOutcome(
        Decision decision,
        List<String> reasonCodes,
        String policyVersion,
        Optional<GateFailure> fallback,
        boolean reviewLater) {

    public GateOutcome {
        Objects.requireNonNull(decision, "decision");
        reasonCodes = List.copyOf(reasonCodes);
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(fallback, "fallback");
    }
}
