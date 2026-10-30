//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Something a detector found that a person must look at: a name close to a sanctioned one, a monitoring scenario
 * that fired, a payment the gate held.
 *
 * @param id        unique; raising the same alert twice (a retried message) changes nothing
 * @param source    the detector, e.g. {@code screening}, {@code monitoring}, {@code gate}
 * @param type      what it found, e.g. {@code SANCTIONS_NAME_MATCH}
 * @param subject   who it is about: the bank's customer id, or another stable reference; alerts about one subject
 *                  are investigated together
 * @param eventId   the transaction that raised it, if any
 * @param score     how strong the evidence is, from 0 to 1, if the detector scores
 * @param evidence  what the analyst needs to judge it, e.g. the list, its version, the matched entry and name
 * @param raisedAt  when
 */
public record Alert(
        String id,
        String source,
        String type,
        String subject,
        Optional<String> eventId,
        Optional<Double> score,
        Map<String, String> evidence,
        Instant raisedAt) {

    public Alert {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(score, "score");
        evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        Objects.requireNonNull(raisedAt, "raisedAt");
    }
}
