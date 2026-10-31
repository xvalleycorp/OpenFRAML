//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.server;

import java.math.BigDecimal;
import java.time.Instant;
import vn.io.dxa.openframl.gate.GateFailure;
import vn.io.dxa.openframl.gate.GateOutcome;
import vn.io.dxa.openframl.gate.v1.AccountRef;
import vn.io.dxa.openframl.gate.v1.Channel;
import vn.io.dxa.openframl.gate.v1.Decision;
import vn.io.dxa.openframl.gate.v1.DecideResponse;
import vn.io.dxa.openframl.gate.v1.Fallback;
import vn.io.dxa.openframl.gate.v1.Money;
import vn.io.dxa.openframl.gate.v1.SessionSignals;
import vn.io.dxa.openframl.gate.v1.Transaction;
import vn.io.dxa.openframl.gate.v1.TransactionType;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * Converts between the wire messages of {@code openframl.gate.v1} and the common model. Proto enums carry a prefix
 * ({@code CHANNEL_MOBILE}); the model's constants are the same names without it.
 */
final class ProtoMapping {

    private ProtoMapping() {}

    /**
     * @throws IllegalArgumentException if the transaction is incomplete or invalid; the message names the field and
     *                                  carries no account number
     */
    static TransactionEvent event(Transaction tx) {
        require(tx.hasOccurredAt(), "occurred_at");
        require(tx.hasAmount(), "amount");
        require(tx.hasOriginator(), "originator");
        return new TransactionEvent(
                tx.getSchemaVersion(),
                text(tx.getEventId(), "event_id"),
                Instant.ofEpochSecond(tx.getOccurredAt().getSeconds(), tx.getOccurredAt().getNanos()),
                vn.io.dxa.openframl.model.Channel.valueOf(unprefixed(tx.getChannel(), Channel.CHANNEL_UNSPECIFIED,
                        "CHANNEL_", "channel")),
                vn.io.dxa.openframl.model.TransactionType.valueOf(unprefixed(tx.getType(),
                        TransactionType.TRANSACTION_TYPE_UNSPECIFIED, "TRANSACTION_TYPE_", "type")),
                amount(tx.getAmount()),
                text(tx.getCustomerId(), "customer_id"),
                account(tx.getOriginator(), "originator"),
                tx.hasBeneficiary() ? account(tx.getBeneficiary(), "beneficiary") : null,
                tx.hasSession() ? session(tx.getSession()) : null,
                tx.getBeneficiaryName(),
                tx.getCounterpartyCountry());
    }

    static DecideResponse response(GateOutcome outcome) {
        return DecideResponse.newBuilder()
                .setDecision(Decision.valueOf("DECISION_" + outcome.decision().name()))
                .addAllReasonCodes(outcome.reasonCodes())
                .setPolicyVersion(outcome.policyVersion())
                .setFallback(outcome.fallback().map(ProtoMapping::fallback).orElse(Fallback.FALLBACK_UNSPECIFIED))
                .setReviewLater(outcome.reviewLater())
                .build();
    }

    private static Fallback fallback(GateFailure failure) {
        return Fallback.valueOf("FALLBACK_" + failure.name());
    }

    private static Amount amount(Money money) {
        String value = text(money.getValue(), "amount.value");
        if (!value.matches("\\d+(\\.\\d+)?")) {
            throw new IllegalArgumentException("amount.value must be a plain decimal such as 1500000 or 12.50");
        }
        return new Amount(new BigDecimal(value), text(money.getCurrency(), "amount.currency"));
    }

    private static vn.io.dxa.openframl.model.AccountRef account(AccountRef ref, String field) {
        return new vn.io.dxa.openframl.model.AccountRef(text(ref.getInstitution(), field + ".institution"),
                text(ref.getAccount(), field + ".account"));
    }

    private static vn.io.dxa.openframl.model.SessionSignals session(SessionSignals s) {
        return new vn.io.dxa.openframl.model.SessionSignals(emptyToNull(s.getDeviceId()),
                emptyToNull(s.getIpAddress()), s.getRemoteAccessDetected(), s.getActiveCallDetected(),
                emptyToNull(s.getAuthenticationMethod()));
    }

    private static <E extends Enum<E> & com.google.protobuf.ProtocolMessageEnum> String unprefixed(
            E value, E unspecified, String prefix, String field) {
        if (value == unspecified || value.name().equals("UNRECOGNIZED")) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.name().substring(prefix.length());
    }

    private static String text(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static void require(boolean present, String field) {
        if (!present) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
