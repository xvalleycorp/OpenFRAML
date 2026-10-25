//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import vn.io.dxa.openframl.gate.AccountProfile;
import vn.io.dxa.openframl.gate.Gate;
import vn.io.dxa.openframl.gate.GateFailure;
import vn.io.dxa.openframl.gate.ProfileStore;
import vn.io.dxa.openframl.gate.memory.InMemoryProfileStore;
import vn.io.dxa.openframl.gate.memory.InMemoryVelocityStore;
import vn.io.dxa.openframl.gate.v1.AccountRef;
import vn.io.dxa.openframl.gate.v1.Channel;
import vn.io.dxa.openframl.gate.v1.DecideRequest;
import vn.io.dxa.openframl.gate.v1.DecideResponse;
import vn.io.dxa.openframl.gate.v1.Decision;
import vn.io.dxa.openframl.gate.v1.Fallback;
import vn.io.dxa.openframl.gate.v1.GateServiceGrpc;
import vn.io.dxa.openframl.gate.v1.Money;
import vn.io.dxa.openframl.gate.v1.SessionSignals;
import vn.io.dxa.openframl.gate.v1.Transaction;
import vn.io.dxa.openframl.gate.v1.TransactionType;
import vn.io.dxa.openframl.pack.CountryPack;

class GateGrpcServiceTest {

    private static final vn.io.dxa.openframl.model.AccountRef CUSTOMER =
            new vn.io.dxa.openframl.model.AccountRef("999901", "0123456789");
    private static final vn.io.dxa.openframl.model.AccountRef FRIEND =
            new vn.io.dxa.openframl.model.AccountRef("999902", "9876543210");

    private Gate gate;
    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void stop() throws InterruptedException {
        if (gate == null) {
            return;
        }
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        gate.close();
    }

    private GateServiceGrpc.GateServiceBlockingStub serve(ProfileStore profiles, Duration replyMargin)
            throws Exception {
        gate = Gate.builder(CountryPack.forCountry("VN")).profiles(profiles).velocity(new InMemoryVelocityStore())
                .build();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(new GateGrpcService(gate, Duration.ofSeconds(1), replyMargin)).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        return GateServiceGrpc.newBlockingStub(channel);
    }

    private static InMemoryProfileStore profiles() {
        return new InMemoryProfileStore().account(CUSTOMER, new AccountProfile(LocalDate.of(2018, 5, 2), "LOW"))
                .knownBeneficiary(CUSTOMER, FRIEND).knownDevice("cust-1", "phone-1");
    }

    private static Transaction.Builder transfer(String amount) {
        return Transaction.newBuilder().setSchemaVersion("1").setEventId("evt-1")
                .setOccurredAt(Timestamp.newBuilder().setSeconds(1_792_900_000L))
                .setChannel(Channel.CHANNEL_MOBILE).setType(TransactionType.TRANSACTION_TYPE_TRANSFER_OUT)
                .setAmount(Money.newBuilder().setValue(amount).setCurrency("VND")).setCustomerId("cust-1")
                .setOriginator(AccountRef.newBuilder().setInstitution("999901").setAccount("0123456789"))
                .setBeneficiary(AccountRef.newBuilder().setInstitution("999902").setAccount("9876543210"))
                .setSession(SessionSignals.newBuilder().setDeviceId("phone-1").setActiveCallDetected(true));
    }

    private static DecideRequest request(Transaction.Builder transaction) {
        return DecideRequest.newBuilder().setTransaction(transaction).build();
    }

    @Test
    void decidesAPayment() throws Exception {
        GateServiceGrpc.GateServiceBlockingStub gateStub = serve(profiles(), Duration.ofMillis(1));
        DecideResponse response = gateStub.withDeadlineAfter(1, TimeUnit.SECONDS).decide(request(transfer("2000000")));

        assertEquals(Decision.DECISION_ALLOW, response.getDecision());
        assertEquals(Fallback.FALLBACK_UNSPECIFIED, response.getFallback());
        assertTrue(response.getPolicyVersion().startsWith("gate_decision@1@sha256:"), response.getPolicyVersion());

        DecideResponse large = gateStub.decide(request(transfer("60000000").setBeneficiary(
                AccountRef.newBuilder().setInstitution("999902").setAccount("1111222233"))));
        assertEquals(Decision.DECISION_CHALLENGE, large.getDecision());
        assertEquals(java.util.List.of("ACTIVE_CALL_NEW_BENEFICIARY", "NEW_BENEFICIARY_LARGE_AMOUNT"),
                large.getReasonCodesList());
    }

    @Test
    void theCallersDeadlineIsTheGatesBudget() throws Exception {
        InMemoryProfileStore known = profiles();
        ProfileStore slow = event -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return known.lookup(event);
        };
        // A 300 ms deadline less a 250 ms reply margin leaves 50 ms: the 80 ms lookup overruns it.
        GateServiceGrpc.GateServiceBlockingStub gateStub = serve(slow, Duration.ofMillis(250));
        DecideResponse late = gateStub.withDeadlineAfter(300, TimeUnit.MILLISECONDS)
                .decide(request(transfer("2000000")));
        assertEquals(Fallback.FALLBACK_TIMEOUT, late.getFallback());
        assertEquals(Decision.DECISION_ALLOW, late.getDecision());
        assertTrue(late.getReviewLater());

        // Without a deadline the default budget of 1 s applies.
        assertEquals(Fallback.FALLBACK_UNSPECIFIED, gateStub.decide(request(transfer("2000000"))).getFallback());
    }

    @Test
    void anInvalidTransactionIsRejectedWithoutEchoingTheAccount() throws Exception {
        GateServiceGrpc.GateServiceBlockingStub gateStub = serve(profiles(), Duration.ofMillis(1));
        assertInvalid("amount is required", () -> gateStub.decide(request(transfer("1").clearAmount())));
        assertInvalid("channel is required", () -> gateStub.decide(request(transfer("1").clearChannel())));
        assertInvalid("amount.value must be a plain decimal such as 1500000 or 12.50",
                () -> gateStub.decide(request(transfer("1e6"))));
        assertInvalid("unsupported schema version 2; this build reads version 1",
                () -> gateStub.decide(request(transfer("1").setSchemaVersion("2"))));
        assertInvalid("currency must be an upper-case ISO 4217 code: dong",
                () -> gateStub.decide(request(transfer("1").setAmount(
                        Money.newBuilder().setValue("1").setCurrency("dong")))));
    }

    private static void assertInvalid(String message, Executable call) {
        StatusRuntimeException error = org.junit.jupiter.api.Assertions.assertThrows(StatusRuntimeException.class,
                call);
        assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
        assertEquals(message, error.getStatus().getDescription());
        assertFalse(error.getMessage().contains("0123456789"));
    }

    @Test
    void everyModelConstantHasAWireConstant() {
        for (var value : vn.io.dxa.openframl.model.Channel.values()) {
            Channel.valueOf("CHANNEL_" + value.name());
        }
        for (var value : vn.io.dxa.openframl.model.TransactionType.values()) {
            TransactionType.valueOf("TRANSACTION_TYPE_" + value.name());
        }
        for (var value : vn.io.dxa.openframl.model.Decision.values()) {
            Decision.valueOf("DECISION_" + value.name());
        }
        for (var value : GateFailure.values()) {
            Fallback.valueOf("FALLBACK_" + value.name());
        }
        // ...and the other way round, apart from the zero values
        assertEquals(vn.io.dxa.openframl.model.Channel.values().length + 2, Channel.values().length);
        assertEquals(vn.io.dxa.openframl.model.TransactionType.values().length + 2, TransactionType.values().length);
        assertEquals(vn.io.dxa.openframl.model.Decision.values().length + 2, Decision.values().length);
        assertEquals(GateFailure.values().length + 2, Fallback.values().length);
    }
}
