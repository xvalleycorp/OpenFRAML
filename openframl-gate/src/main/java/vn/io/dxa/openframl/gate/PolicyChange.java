//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.util.Objects;

/**
 * One change of the policy a gate runs, as written to the audit trail.
 *
 * @param kind     an activation of a new policy, or a rollback to the previous one
 * @param fromId   the policy in force before the change
 * @param toId     the policy in force after it
 * @param approval who prepared and who approved it
 */
public record PolicyChange(Kind kind, String fromId, String toId, Approval approval) {

    public enum Kind {
        ACTIVATE,
        ROLLBACK
    }

    public PolicyChange {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fromId, "fromId");
        Objects.requireNonNull(toId, "toId");
        Objects.requireNonNull(approval, "approval");
    }
}
