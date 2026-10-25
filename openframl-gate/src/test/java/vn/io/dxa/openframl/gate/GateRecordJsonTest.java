//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.model.Decision;

class GateRecordJsonTest {

    private static GateRecord record(Optional<GateFailure> fallback, Optional<BigDecimal> score,
            Optional<WatchlistHit> hit) {
        return new GateRecord("evt-1", Instant.parse("2026-10-25T03:00:00Z"),
                new GateOutcome(Decision.HOLD, List.of("BENEFICIARY_ON_WATCHLIST"), "gate_decision@1@sha256:ab",
                        fallback, false),
                score.map(s -> "fraud-xgb-3"), score, hit, Map.of("watchlist", 1_000L), List.of());
    }

    @Test
    void writesEveryFieldUnderTheSchemaId() throws Exception {
        String line = GateRecordJson.write(record(Optional.of(GateFailure.TIMEOUT),
                Optional.of(new BigDecimal("0.95")), Optional.of(new WatchlistHit("REGULATOR", "2026-10-24.1"))));
        JsonNode json = new ObjectMapper().readTree(line);

        assertFalse(line.contains("\n"));
        assertEquals("openframl.gate.record/1", json.get("schema").asText());
        assertEquals("evt-1", json.get("eventId").asText());
        assertEquals("2026-10-25T03:00:00Z", json.get("occurredAt").asText());
        assertEquals("HOLD", json.get("decision").asText());
        assertEquals("BENEFICIARY_ON_WATCHLIST", json.get("reasonCodes").get(0).asText());
        assertEquals("TIMEOUT", json.get("fallback").asText());
        assertEquals("fraud-xgb-3", json.get("modelVersion").asText());
        assertEquals(new BigDecimal("0.95"), json.get("score").decimalValue());
        assertEquals("REGULATOR", json.get("watchlist").get("source").asText());
        assertEquals(1_000L, json.get("stepNanos").get("watchlist").asLong());
        assertEquals(1_000L, json.get("totalNanos").asLong());
        assertTrue(json.get("rules").isArray());
    }

    @Test
    void leavesOutWhatIsAbsent() throws Exception {
        JsonNode json = new ObjectMapper().readTree(GateRecordJson.write(
                record(Optional.empty(), Optional.empty(), Optional.empty())));

        for (String field : List.of("fallback", "modelVersion", "score", "watchlist")) {
            assertFalse(json.has(field), field);
        }
    }
}
