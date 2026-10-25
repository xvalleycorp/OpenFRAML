//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.rulestone.api.DecisionRecord;

/**
 * The audit record of one gate decision. Like RuleStone's own records, it carries no input values: no account
 * numbers, names or amounts. The event id links it to the payment.
 *
 * @param eventId     the payment's event id
 * @param occurredAt  when the payment was confirmed
 * @param outcome     what the gate answered
 * @param modelVersion the model that scored the payment, if a score arrived in time
 * @param score       the score, if it arrived in time
 * @param watchlist   the list the beneficiary was found on, if any
 * @param stepNanos   time spent in each step, in order, for latency budgets
 * @param rules       RuleStone's records of the decision tables evaluated
 */
public record GateRecord(
        String eventId,
        Instant occurredAt,
        GateOutcome outcome,
        Optional<String> modelVersion,
        Optional<BigDecimal> score,
        Optional<WatchlistHit> watchlist,
        Map<String, Long> stepNanos,
        List<DecisionRecord> rules) {

    public GateRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(modelVersion, "modelVersion");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(watchlist, "watchlist");
        stepNanos = Collections.unmodifiableMap(new LinkedHashMap<>(stepNanos));
        rules = List.copyOf(rules);
    }

    /** Total time from receiving the request to the decision. */
    public long totalNanos() {
        return stepNanos.values().stream().mapToLong(Long::longValue).sum();
    }
}
