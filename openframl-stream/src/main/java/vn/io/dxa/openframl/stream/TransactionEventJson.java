//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.SessionSignals;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;

/**
 * The open JSON form of a {@link TransactionEvent}, as the bank's payment hub publishes it to the transaction topic.
 * It carries the same fields as the gate's gRPC {@code Transaction}, by their model names; optional fields that are
 * absent are left out.
 *
 * <pre>{@code
 * {"schema": "openframl.transaction/1", "eventId": "evt-1", "occurredAt": "2026-10-30T02:00:00Z",
 *  "channel": "MOBILE", "type": "TRANSFER_OUT", "amount": {"value": "1500000", "currency": "VND"},
 *  "customerId": "cust-1", "originator": {"institution": "999901", "account": "0123456789"},
 *  "beneficiary": {"institution": "999902", "account": "5555666677"}}
 * }</pre>
 *
 * <p>Amounts are decimal strings, never JSON numbers, so no floating point touches them. A message that cannot be
 * read is rejected with the field that is wrong; the message never repeats an account number.
 */
public final class TransactionEventJson {

    /** The schema id of model version {@value TransactionEvent#SCHEMA_VERSION}. */
    public static final String SCHEMA = "openframl.transaction/1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TransactionEventJson() {}

    public static ObjectNode toJson(TransactionEvent event) {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("schema", SCHEMA);
        json.put("eventId", event.eventId());
        json.put("occurredAt", event.occurredAt().toString());
        json.put("channel", event.channel().name());
        json.put("type", event.type().name());
        json.putObject("amount").put("value", event.amount().value().toPlainString())
                .put("currency", event.amount().currency());
        json.put("customerId", event.customerId());
        account(json.putObject("originator"), event.originator());
        if (event.beneficiary() != null) {
            account(json.putObject("beneficiary"), event.beneficiary());
        }
        SessionSignals session = event.session();
        if (!session.equals(SessionSignals.NONE)) {
            ObjectNode s = json.putObject("session");
            if (session.deviceId() != null) {
                s.put("deviceId", session.deviceId());
            }
            if (session.ipAddress() != null) {
                s.put("ipAddress", session.ipAddress());
            }
            s.put("remoteAccessDetected", session.remoteAccessDetected());
            s.put("activeCallDetected", session.activeCallDetected());
            if (session.authenticationMethod() != null) {
                s.put("authenticationMethod", session.authenticationMethod());
            }
        }
        if (event.beneficiaryName() != null) {
            json.put("beneficiaryName", event.beneficiaryName());
        }
        if (event.counterpartyCountry() != null) {
            json.put("counterpartyCountry", event.counterpartyCountry());
        }
        return json;
    }

    /** The event as one line of JSON. */
    public static String write(TransactionEvent event) {
        return toJson(event).toString();
    }

    /**
     * @throws IllegalArgumentException if the message is not an event of this schema; the message names the field
     */
    public static TransactionEvent read(String message) {
        JsonNode json;
        try {
            json = MAPPER.readTree(message);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not JSON, at line " + e.getLocation().getLineNr() + " column "
                    + e.getLocation().getColumnNr());
        }
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        if (!SCHEMA.equals(json.path("schema").asText(null))) {
            throw new IllegalArgumentException("schema must be " + SCHEMA);
        }
        TransactionEvent.Builder event = TransactionEvent.builder()
                .eventId(text(json, "eventId"))
                .occurredAt(instant(json, "occurredAt"))
                .channel(constant(Channel.class, json, "channel"))
                .type(constant(TransactionType.class, json, "type"))
                .amount(amount(json))
                .customerId(text(json, "customerId"))
                .originator(account(json, "originator"));
        if (json.hasNonNull("beneficiary")) {
            event.beneficiary(account(json, "beneficiary"));
        }
        JsonNode session = json.path("session");
        if (session.isObject()) {
            event.session(new SessionSignals(optional(session, "deviceId"), optional(session, "ipAddress"),
                    session.path("remoteAccessDetected").asBoolean(false),
                    session.path("activeCallDetected").asBoolean(false), optional(session, "authenticationMethod")));
        }
        event.beneficiaryName(optional(json, "beneficiaryName"));
        event.counterpartyCountry(optional(json, "counterpartyCountry"));
        try {
            return event.build();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private static void account(ObjectNode json, AccountRef account) {
        json.put("institution", account.institution());
        json.put("account", account.account());
    }

    private static AccountRef account(JsonNode json, String field) {
        JsonNode node = json.path(field);
        if (!node.isObject()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return new AccountRef(text(node, "institution", field + ".institution"),
                text(node, "account", field + ".account"));
    }

    private static Amount amount(JsonNode json) {
        JsonNode node = json.path("amount");
        String value = text(node, "value", "amount.value");
        if (!node.path("value").isTextual() || !value.matches("\\d+(\\.\\d+)?")) {
            throw new IllegalArgumentException("amount.value must be a decimal string such as \"1500000\" or "
                    + "\"12.50\"");
        }
        return new Amount(new BigDecimal(value), text(node, "currency", "amount.currency"));
    }

    private static Instant instant(JsonNode json, String field) {
        try {
            return Instant.parse(text(json, field));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be an ISO 8601 instant such as 2026-10-30T02:00:00Z");
        }
    }

    private static <E extends Enum<E>> E constant(Class<E> type, JsonNode json, String field) {
        String value = text(json, field);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is not a known " + type.getSimpleName() + ": " + value);
        }
    }

    private static String text(JsonNode json, String field) {
        return text(json, field, field);
    }

    private static String text(JsonNode json, String field, String name) {
        JsonNode node = json.path(field);
        if (!node.isValueNode() || node.isNull() || node.asText().isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return node.asText();
    }

    private static String optional(JsonNode json, String field) {
        JsonNode node = json.path(field);
        return node.isValueNode() && !node.isNull() ? node.asText() : null;
    }
}
