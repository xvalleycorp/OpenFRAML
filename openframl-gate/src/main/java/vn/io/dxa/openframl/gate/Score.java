//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A model's fraud score for one payment. The score is an input to the decision table, never the decision itself.
 *
 * @param modelVersion the model that produced it, recorded with the decision
 * @param fraud        the probability that the payment is fraudulent, from 0 to 1
 */
public record Score(String modelVersion, BigDecimal fraud) {

    public Score {
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(fraud, "fraud");
        if (fraud.signum() < 0 || fraud.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("fraud score must be between 0 and 1: " + fraud);
        }
    }
}
