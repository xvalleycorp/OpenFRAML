//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.model;

/**
 * What the channel knows about the session a payment came from. Every field is optional: a channel that cannot
 * observe a signal leaves it {@code null} or {@code false}.
 *
 * @param deviceId             the channel's stable identifier for the device, or {@code null}
 * @param ipAddress            the client IP address, or {@code null}
 * @param remoteAccessDetected a screen-sharing or remote-control app was active during the session
 * @param activeCallDetected   the customer was on a phone call while confirming the payment
 * @param authenticationMethod how the customer confirmed the payment (e.g. {@code BIOMETRIC}, {@code OTP}), or
 *                             {@code null}
 */
public record SessionSignals(
        String deviceId,
        String ipAddress,
        boolean remoteAccessDetected,
        boolean activeCallDetected,
        String authenticationMethod) {

    /** No signals: a channel that observes nothing about the session. */
    public static final SessionSignals NONE = new SessionSignals(null, null, false, false, null);
}
