//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.rulestone.api.Facts;
import vn.io.dxa.rulestone.api.Money;

/**
 * The facts the gate passes to its decision tables, filled in step by step. A step that did not run, or failed,
 * leaves its fields unset, and the tables test them with {@code has(...)}. The contract is documented in
 * {@code docs/gate.md}.
 */
final class GateFacts {

    private final LocalDate businessDate;
    private final Map<String, Object> transaction = new LinkedHashMap<>();
    private final Map<String, Object> originator = new LinkedHashMap<>();
    private final Map<String, Object> beneficiary = new LinkedHashMap<>();
    private final Map<String, Object> device = new LinkedHashMap<>();
    private final Map<String, Object> velocity = new LinkedHashMap<>();
    private final Map<String, Object> score = new LinkedHashMap<>();
    private GateFailure failure;

    GateFacts(TransactionEvent event, LocalDate businessDate) {
        this.businessDate = businessDate;
        transaction.put("amount", money(event.amount()));
        transaction.put("type", event.type().name());
        transaction.put("channel", event.channel().name());
        if (event.beneficiary() != null) {
            beneficiary.put("sameInstitution",
                    event.beneficiary().institution().equals(event.originator().institution()));
        }
        device.put("remoteAccess", event.session().remoteAccessDetected());
        device.put("activeCall", event.session().activeCallDetected());
    }

    void watchlist(Optional<WatchlistHit> hit) {
        beneficiary.put("watchlisted", hit.isPresent());
        hit.ifPresent(found -> beneficiary.put("watchlistSource", found.source()));
    }

    void profiles(TransactionEvent event, ProfileSnapshot profiles) {
        profiles.originator().ifPresent(profile -> account(originator, profile));
        if (event.beneficiary() != null) {
            profiles.beneficiary().ifPresent(profile -> account(beneficiary, profile));
            beneficiary.put("known", profiles.beneficiaryKnown());
        }
        if (event.session().deviceId() != null) {
            device.put("known", profiles.deviceKnown());
        }
    }

    void velocity(VelocitySnapshot snapshot) {
        velocity.put("count10m", snapshot.count10m());
        velocity.put("count24h", snapshot.count24h());
        velocity.put("amount24h", money(snapshot.amount24h()));
        velocity.put("distinctBeneficiaries24h", snapshot.distinctBeneficiaries24h());
    }

    void score(Score scored) {
        score.put("fraud", scored.fraud());
        score.put("modelVersion", scored.modelVersion());
    }

    void failure(GateFailure cause) {
        failure = cause;
    }

    boolean watchlisted() {
        return Boolean.TRUE.equals(beneficiary.get("watchlisted"));
    }

    Facts toFacts() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("transaction", transaction);
        facts.put("originator", originator);
        facts.put("beneficiary", beneficiary);
        facts.put("device", device);
        facts.put("velocity", velocity);
        facts.put("score", score);
        if (failure != null) {
            facts.put("failure", failure.name());
        }
        return Facts.from(facts);
    }

    private void account(Map<String, Object> party, AccountProfile profile) {
        party.put("accountAgeDays", ChronoUnit.DAYS.between(profile.openedOn(), businessDate));
        if (profile.riskRating() != null) {
            party.put("riskRating", profile.riskRating());
        }
    }

    private static Money money(Amount amount) {
        return Money.of(amount.value(), amount.currency());
    }
}
