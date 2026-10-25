//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.io.dxa.rulestone.api.DecisionRecord;
import vn.io.dxa.rulestone.core.DecisionRecordJson;

/**
 * The open, versioned JSON form of a {@link GateRecord}, as published to Kafka or written to a file. A bank keeps
 * its decision records in this form and can read them without OpenFRAML.
 *
 * <p>Field names are stable within a {@link #SCHEMA}; a change that removes or renames a field raises the version.
 * Optional values that are absent are left out. The {@code rules} are RuleStone's own decision records, in
 * RuleStone's JSON form.
 */
public final class GateRecordJson {

    /** The schema id written in every record. */
    public static final String SCHEMA = "openframl.gate.record/1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GateRecordJson() {}

    public static ObjectNode toJson(GateRecord record) {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("schema", SCHEMA);
        json.put("eventId", record.eventId());
        json.put("occurredAt", record.occurredAt().toString());

        GateOutcome outcome = record.outcome();
        json.put("decision", outcome.decision().name());
        ArrayNode reasons = json.putArray("reasonCodes");
        outcome.reasonCodes().forEach(reasons::add);
        json.put("policyVersion", outcome.policyVersion());
        outcome.fallback().ifPresent(failure -> json.put("fallback", failure.name()));
        json.put("reviewLater", outcome.reviewLater());

        record.modelVersion().ifPresent(version -> json.put("modelVersion", version));
        record.score().ifPresent(score -> json.put("score", score));
        record.watchlist().ifPresent(hit -> json.putObject("watchlist")
                .put("source", hit.source()).put("listVersion", hit.listVersion()));

        ObjectNode steps = json.putObject("stepNanos");
        record.stepNanos().forEach(steps::put);
        json.put("totalNanos", record.totalNanos());

        ArrayNode rules = json.putArray("rules");
        for (DecisionRecord rule : record.rules()) {
            rules.add(DecisionRecordJson.toJson(rule));
        }
        return json;
    }

    /** The record as one line of JSON. */
    public static String write(GateRecord record) {
        return toJson(record).toString();
    }
}
