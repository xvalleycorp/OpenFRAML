//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.model;

import java.time.Instant;
import java.util.Objects;

/**
 * A transaction in the common model that every channel adapter (ISO 20022, ISO 8583, open API) normalises to.
 *
 * <p>The model is versioned by {@link #SCHEMA_VERSION}: a change that is not backwards compatible gets a new
 * version, and consumers reject versions they do not know.
 *
 * <p>{@code occurredAt} is set by the bank's own system (the payment hub), not by the customer's device: decisions
 * use it for effective dating and velocity windows, so replaying an event gives the same result.
 *
 * @param schemaVersion the model version, {@link #SCHEMA_VERSION}
 * @param eventId       unique and stable across retries; correlates the decision with the payment
 * @param occurredAt    when the customer confirmed the transaction
 * @param channel       where it was initiated
 * @param type          what it does
 * @param amount        how much
 * @param customerId    the originating customer
 * @param originator    the account the money leaves (or, for {@link TransactionType#TRANSFER_IN}, the account it
 *                      arrives at)
 * @param beneficiary   the counterparty account, or {@code null} for cash
 * @param session       signals about the session; {@link SessionSignals#NONE} when there are none
 * @param beneficiaryName the counterparty's name as the payment carries it (e.g. from the scheme's name inquiry),
 *                      or {@code null}; for screening only, never logged or recorded with a decision
 */
public record TransactionEvent(
        String schemaVersion,
        String eventId,
        Instant occurredAt,
        Channel channel,
        TransactionType type,
        Amount amount,
        String customerId,
        AccountRef originator,
        AccountRef beneficiary,
        SessionSignals session,
        String beneficiaryName) {

    /** The current version of this model. */
    public static final String SCHEMA_VERSION = "1";

    public TransactionEvent {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported schema version " + schemaVersion
                    + "; this build reads version " + SCHEMA_VERSION);
        }
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(originator, "originator");
        session = session == null ? SessionSignals.NONE : session;
        beneficiaryName = beneficiaryName == null || beneficiaryName.isBlank() ? null : beneficiaryName.strip();
    }

    /** Every field but the beneficiary's name, which is personal data; account numbers are masked. */
    @Override
    public String toString() {
        return "TransactionEvent[schemaVersion=" + schemaVersion + ", eventId=" + eventId + ", occurredAt="
                + occurredAt + ", channel=" + channel + ", type=" + type + ", amount=" + amount + ", customerId="
                + customerId + ", originator=" + originator + ", beneficiary=" + beneficiary + ", session=" + session
                + ", beneficiaryName=" + (beneficiaryName == null ? "none" : "redacted") + "]";
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builds an event of the current {@link #SCHEMA_VERSION}. */
    public static final class Builder {

        private String eventId;
        private Instant occurredAt;
        private Channel channel;
        private TransactionType type;
        private Amount amount;
        private String customerId;
        private AccountRef originator;
        private AccountRef beneficiary;
        private SessionSignals session = SessionSignals.NONE;
        private String beneficiaryName;

        private Builder() {}

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public Builder channel(Channel channel) {
            this.channel = channel;
            return this;
        }

        public Builder type(TransactionType type) {
            this.type = type;
            return this;
        }

        public Builder amount(Amount amount) {
            this.amount = amount;
            return this;
        }

        public Builder customerId(String customerId) {
            this.customerId = customerId;
            return this;
        }

        public Builder originator(AccountRef originator) {
            this.originator = originator;
            return this;
        }

        public Builder beneficiary(AccountRef beneficiary) {
            this.beneficiary = beneficiary;
            return this;
        }

        public Builder session(SessionSignals session) {
            this.session = session;
            return this;
        }

        public Builder beneficiaryName(String beneficiaryName) {
            this.beneficiaryName = beneficiaryName;
            return this;
        }

        public TransactionEvent build() {
            return new TransactionEvent(SCHEMA_VERSION, eventId, occurredAt, channel, type, amount, customerId,
                    originator, beneficiary, session, beneficiaryName);
        }
    }
}
