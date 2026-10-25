//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.time.Instant;
import java.util.Objects;

/**
 * Four-eyes approval of a policy change: the person who prepared it and the person who approved it must differ.
 *
 * @param maker    who prepared the change
 * @param checker  who approved it; never the maker
 * @param reason   why, for the audit trail (e.g. the change ticket)
 * @param approvedAt when it was approved
 */
public record Approval(String maker, String checker, String reason, Instant approvedAt) {

    public Approval {
        requireText(maker, "maker");
        requireText(checker, "checker");
        requireText(reason, "reason");
        Objects.requireNonNull(approvedAt, "approvedAt");
        if (maker.strip().equalsIgnoreCase(checker.strip())) {
            throw new IllegalArgumentException("the checker must not be the maker: " + maker);
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
