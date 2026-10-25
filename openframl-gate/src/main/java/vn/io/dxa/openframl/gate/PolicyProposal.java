//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A policy change prepared by a maker and waiting for, or given, a checker's decision.
 *
 * @param id             the proposal's id
 * @param kind           a new policy, or a return to the one before
 * @param fromId         the policy in force when it was proposed; it can only be approved while that is still so
 * @param toId           the policy it puts in force
 * @param maker          who proposed it
 * @param reason         why, e.g. the change ticket
 * @param proposedAt     when
 * @param status         pending, approved or rejected
 * @param decidedBy      the checker who approved or rejected it
 * @param decidedAt      when
 * @param decisionReason why it was rejected
 */
public record PolicyProposal(
        String id,
        PolicyChange.Kind kind,
        String fromId,
        String toId,
        String maker,
        String reason,
        Instant proposedAt,
        Status status,
        Optional<String> decidedBy,
        Optional<Instant> decidedAt,
        Optional<String> decisionReason) {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    public PolicyProposal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fromId, "fromId");
        Objects.requireNonNull(toId, "toId");
        Objects.requireNonNull(maker, "maker");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(proposedAt, "proposedAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(decisionReason, "decisionReason");
    }

    /** A new, pending proposal. */
    public static PolicyProposal pending(String id, PolicyChange.Kind kind, String fromId, String toId, String maker,
            String reason, Instant proposedAt) {
        return new PolicyProposal(id, kind, fromId, toId, maker, reason, proposedAt, Status.PENDING,
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
