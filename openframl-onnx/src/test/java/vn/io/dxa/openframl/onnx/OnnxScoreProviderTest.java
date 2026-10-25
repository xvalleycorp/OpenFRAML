//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.gate.AccountProfile;
import vn.io.dxa.openframl.gate.ProfileSnapshot;
import vn.io.dxa.openframl.gate.Score;
import vn.io.dxa.openframl.gate.VelocitySnapshot;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Amount;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.SessionSignals;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;

class OnnxScoreProviderTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AccountRef ALICE = new AccountRef("999901", "0123456789");
    private static final AccountRef MULE = new AccountRef("999902", "5555666677");

    private static final TransactionEvent PAYMENT = TransactionEvent.builder().eventId("evt-1")
            .occurredAt(Instant.parse("2026-10-25T16:30:00Z")).channel(Channel.MOBILE)
            .type(TransactionType.TRANSFER_OUT).amount(Amount.of(5_000_000, "XTS")).customerId("alice")
            .originator(ALICE).beneficiary(MULE)
            .session(new SessionSignals("phone-9", null, true, false, "OTP")).build();
    private static final ProfileSnapshot PROFILES = new ProfileSnapshot(
            Optional.of(new AccountProfile(LocalDate.of(2020, 1, 15), "LOW")),
            Optional.of(new AccountProfile(LocalDate.of(2026, 10, 20), "HIGH")), false, false);
    private static final VelocitySnapshot VELOCITY = new VelocitySnapshot(3, 7, Amount.of(9_000_000, "XTS"), 4);

    private static Map<String, String> contract(String features) {
        return Map.of(OnnxScoreProvider.FEATURES, features, OnnxScoreProvider.VERSION, "fraud-tiny-1");
    }

    private static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    @Test
    void computesEachFeatureFromThePayment() {
        ScoreFeatures.Inputs in = new ScoreFeatures.Inputs(PAYMENT, PROFILES, VELOCITY, ZONE);
        Map<String, Double> expected = Map.ofEntries(Map.entry("amount", 5_000_000.0),
                Map.entry("count_10m", 3.0), Map.entry("distinct_beneficiaries_24h", 4.0),
                Map.entry("originator_age_days", 2475.0), Map.entry("beneficiary_age_days", 5.0),
                Map.entry("beneficiary_risk_high", 1.0), Map.entry("originator_risk_high", 0.0),
                Map.entry("beneficiary_known", 0.0), Map.entry("same_institution", 0.0),
                Map.entry("device_known", 0.0), Map.entry("has_device", 1.0), Map.entry("remote_access", 1.0),
                Map.entry("hour_of_day", 23.0), Map.entry("channel_mobile", 1.0), Map.entry("channel_atm", 0.0),
                Map.entry("type_transfer_out", 1.0));
        List<String> names = List.copyOf(expected.keySet());
        List<ScoreFeatures.Feature> features = ScoreFeatures.resolve(names);
        for (int i = 0; i < names.size(); i++) {
            assertEquals(expected.get(names.get(i)), features.get(i).value(in), names.get(i));
        }
    }

    @Test
    void scoresWithTheFeaturesTheModelNames() throws Exception {
        byte[] model = TinyModels.logistic(new float[] {2.0f, 1.5f, -0.5f}, -1.0f, 1,
                contract("remote_access, beneficiary_risk_high,count_10m"));
        try (OnnxScoreProvider provider = OnnxScoreProvider.load(model, ZONE)) {
            Score score = provider.score(PAYMENT, PROFILES, VELOCITY);

            assertEquals("fraud-tiny-1", score.modelVersion());
            assertEquals(List.of("remote_access", "beneficiary_risk_high", "count_10m"), provider.featureNames());
            assertEquals(sigmoid(2.0 + 1.5 - 1.5 - 1.0), score.fraud().doubleValue(), 1e-6);
        }
    }

    @Test
    void readsTheFraudClassOfATwoClassOutput() throws Exception {
        byte[] model = TinyModels.logistic(new float[] {3.0f}, 0f, 2, contract("remote_access"));
        try (OnnxScoreProvider provider = OnnxScoreProvider.load(model, ZONE)) {
            assertEquals(sigmoid(3.0), provider.score(PAYMENT, PROFILES, VELOCITY).fraud().doubleValue(), 1e-6);
        }
    }

    @Test
    void aModelThatBreaksItsContractIsRejectedWhenLoaded() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> OnnxScoreProvider.load(
                TinyModels.logistic(new float[] {1f}, 0f, 1, contract("amount_in_usd")), ZONE))
                .getMessage().contains("unknown feature: amount_in_usd"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> OnnxScoreProvider.load(
                TinyModels.logistic(new float[] {1f, 1f}, 0f, 1, contract("amount")), ZONE))
                .getMessage().contains("expected [1, 1]"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> OnnxScoreProvider.load(
                TinyModels.logistic(new float[] {1f}, 0f, 1, Map.of(OnnxScoreProvider.FEATURES, "amount")), ZONE))
                .getMessage().contains(OnnxScoreProvider.VERSION));
    }

    @Test
    void scoresQuicklyEnoughForTheGatesBudget() throws Exception {
        List<String> all = ScoreFeatures.names();
        byte[] model = TinyModels.logistic(new float[all.size()], 0f, 2, contract(String.join(",", all)));
        try (OnnxScoreProvider provider = OnnxScoreProvider.load(model, ZONE)) {
            for (int i = 0; i < 2_000; i++) {
                provider.score(PAYMENT, PROFILES, VELOCITY);
            }
            int runs = 10_000;
            long start = System.nanoTime();
            for (int i = 0; i < runs; i++) {
                provider.score(PAYMENT, PROFILES, VELOCITY);
            }
            Duration mean = Duration.ofNanos((System.nanoTime() - start) / runs);
            // A loose bound for slow CI runners; the gate's default model budget is 5 ms
            assertTrue(mean.compareTo(Duration.ofMillis(1)) < 0, mean::toString);
            assertEquals(new BigDecimal("0.5"), provider.score(PAYMENT, PROFILES, VELOCITY).fraud());
        }
    }
}
