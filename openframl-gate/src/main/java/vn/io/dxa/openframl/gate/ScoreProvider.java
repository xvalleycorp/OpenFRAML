//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * Scores a payment with a model running in the gate's process. The gate gives it a time budget; a score that is
 * late is dropped, and the decision table runs its rules-only branch.
 */
@FunctionalInterface
public interface ScoreProvider {

    /**
     * @throws RuntimeException if the model fails; the decision is then made without a score
     */
    Score score(TransactionEvent event, ProfileSnapshot profiles, VelocitySnapshot velocity);
}
