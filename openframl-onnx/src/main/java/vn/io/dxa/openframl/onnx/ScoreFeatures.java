//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.onnx;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import vn.io.dxa.openframl.gate.AccountProfile;
import vn.io.dxa.openframl.gate.ProfileSnapshot;
import vn.io.dxa.openframl.gate.VelocitySnapshot;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.Channel;
import vn.io.dxa.openframl.model.SessionSignals;
import vn.io.dxa.openframl.model.TransactionEvent;
import vn.io.dxa.openframl.model.TransactionType;

/**
 * The features a model can ask for, by name. A model lists the ones it was trained on, in order; the scorer builds
 * that vector for every payment. The names are a contract with the data scientists: a feature is never renamed or
 * changed in meaning, only added.
 *
 * <p>Amounts are in the payment's currency. Ages are in days at the payment's business date, {@code -1} when the
 * account has no profile. Flags are 0 or 1. One flag per channel ({@code channel_mobile}...) and per transaction type
 * ({@code type_transfer_out}...).
 */
public final class ScoreFeatures {

    /** What a feature is computed from. */
    public record Inputs(TransactionEvent event, ProfileSnapshot profiles, VelocitySnapshot velocity, ZoneId zone) {}

    @FunctionalInterface
    public interface Feature {
        double value(Inputs inputs);
    }

    private static final Map<String, Feature> FEATURES;

    static {
        Map<String, Feature> features = new LinkedHashMap<>();
        features.put("amount", in -> in.event().amount().value().doubleValue());
        features.put("log_amount", in -> Math.log1p(in.event().amount().value().doubleValue()));
        features.put("count_10m", in -> in.velocity().count10m());
        features.put("count_24h", in -> in.velocity().count24h());
        features.put("amount_24h", in -> in.velocity().amount24h().value().doubleValue());
        features.put("log_amount_24h", in -> Math.log1p(in.velocity().amount24h().value().doubleValue()));
        features.put("distinct_beneficiaries_24h", in -> in.velocity().distinctBeneficiaries24h());
        features.put("originator_age_days", in -> ageDays(in.profiles().originator(), in));
        features.put("beneficiary_age_days", in -> ageDays(in.profiles().beneficiary(), in));
        features.put("originator_risk_high", in -> flag(highRisk(in.profiles().originator())));
        features.put("beneficiary_risk_high", in -> flag(highRisk(in.profiles().beneficiary())));
        features.put("beneficiary_known", in -> flag(in.profiles().beneficiaryKnown()));
        features.put("same_institution", in -> {
            AccountRef beneficiary = in.event().beneficiary();
            return flag(beneficiary != null && beneficiary.institution().equals(in.event().originator().institution()));
        });
        features.put("has_device", in -> flag(session(in).deviceId() != null));
        features.put("device_known", in -> flag(session(in).deviceId() != null && in.profiles().deviceKnown()));
        features.put("remote_access", in -> flag(session(in).remoteAccessDetected()));
        features.put("active_call", in -> flag(session(in).activeCallDetected()));
        features.put("hour_of_day", in -> ZonedDateTime.ofInstant(in.event().occurredAt(), in.zone()).getHour());
        for (Channel channel : Channel.values()) {
            features.put("channel_" + channel.name().toLowerCase(Locale.ROOT),
                    in -> flag(in.event().channel() == channel));
        }
        for (TransactionType type : TransactionType.values()) {
            features.put("type_" + type.name().toLowerCase(Locale.ROOT), in -> flag(in.event().type() == type));
        }
        FEATURES = Collections.unmodifiableMap(features);
    }

    private ScoreFeatures() {}

    /** Every feature name, in a stable order. */
    public static List<String> names() {
        return List.copyOf(FEATURES.keySet());
    }

    /**
     * The features {@code names}, in that order.
     *
     * @throws IllegalArgumentException naming the first unknown feature
     */
    public static List<Feature> resolve(List<String> names) {
        return names.stream().map(name -> {
            Feature feature = FEATURES.get(name);
            if (feature == null) {
                throw new IllegalArgumentException("unknown feature: " + name + "; known: " + FEATURES.keySet());
            }
            return feature;
        }).toList();
    }

    private static double ageDays(Optional<AccountProfile> profile, Inputs in) {
        LocalDate today = LocalDate.ofInstant(in.event().occurredAt(), in.zone());
        return profile.map(p -> (double) ChronoUnit.DAYS.between(p.openedOn(), today)).orElse(-1.0);
    }

    private static boolean highRisk(Optional<AccountProfile> profile) {
        return profile.map(p -> "HIGH".equals(p.riskRating())).orElse(false);
    }

    private static SessionSignals session(Inputs in) {
        return in.event().session();
    }

    private static double flag(boolean value) {
        return value ? 1 : 0;
    }
}
