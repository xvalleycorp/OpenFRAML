//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.benchmark;

import com.google.protobuf.Timestamp;
import vn.io.dxa.openframl.gate.v1.AccountRef;
import vn.io.dxa.openframl.gate.v1.Channel;
import vn.io.dxa.openframl.gate.v1.DecideRequest;
import vn.io.dxa.openframl.gate.v1.Money;
import vn.io.dxa.openframl.gate.v1.SessionSignals;
import vn.io.dxa.openframl.gate.v1.Transaction;
import vn.io.dxa.openframl.gate.v1.TransactionType;
import vn.io.dxa.openframl.model.TransactionEvent;

/** Writes a model event as the payment hub would send it. */
final class Requests {

    private Requests() {}

    static DecideRequest of(TransactionEvent event) {
        Transaction.Builder tx = Transaction.newBuilder()
                .setSchemaVersion(event.schemaVersion())
                .setEventId(event.eventId())
                .setOccurredAt(Timestamp.newBuilder().setSeconds(event.occurredAt().getEpochSecond())
                        .setNanos(event.occurredAt().getNano()))
                .setChannel(Channel.valueOf("CHANNEL_" + event.channel().name()))
                .setType(TransactionType.valueOf("TRANSACTION_TYPE_" + event.type().name()))
                .setAmount(Money.newBuilder().setValue(event.amount().value().toPlainString())
                        .setCurrency(event.amount().currency()))
                .setCustomerId(event.customerId())
                .setOriginator(account(event.originator()));
        if (event.beneficiary() != null) {
            tx.setBeneficiary(account(event.beneficiary()));
        }
        var session = event.session();
        SessionSignals.Builder signals = SessionSignals.newBuilder()
                .setRemoteAccessDetected(session.remoteAccessDetected())
                .setActiveCallDetected(session.activeCallDetected());
        if (session.deviceId() != null) {
            signals.setDeviceId(session.deviceId());
        }
        if (session.ipAddress() != null) {
            signals.setIpAddress(session.ipAddress());
        }
        if (session.authenticationMethod() != null) {
            signals.setAuthenticationMethod(session.authenticationMethod());
        }
        return DecideRequest.newBuilder().setTransaction(tx.setSession(signals)).build();
    }

    private static AccountRef account(vn.io.dxa.openframl.model.AccountRef ref) {
        return AccountRef.newBuilder().setInstitution(ref.institution()).setAccount(ref.account()).build();
    }
}
