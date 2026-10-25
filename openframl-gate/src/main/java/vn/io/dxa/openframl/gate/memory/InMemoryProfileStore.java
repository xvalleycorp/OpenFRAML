//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.memory;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import vn.io.dxa.openframl.gate.AccountProfile;
import vn.io.dxa.openframl.gate.ProfileSnapshot;
import vn.io.dxa.openframl.gate.ProfileStore;
import vn.io.dxa.openframl.model.AccountRef;
import vn.io.dxa.openframl.model.TransactionEvent;

/** Profiles in memory, for tests, benchmarks and single-node trials. */
public final class InMemoryProfileStore implements ProfileStore {

    private final ConcurrentHashMap<String, AccountProfile> accounts = new ConcurrentHashMap<>();
    private final Set<String> knownBeneficiaries = ConcurrentHashMap.newKeySet();
    private final Set<String> knownDevices = ConcurrentHashMap.newKeySet();

    @Override
    public ProfileSnapshot lookup(TransactionEvent event) {
        AccountRef beneficiary = event.beneficiary();
        String deviceId = event.session().deviceId();
        return new ProfileSnapshot(
                Optional.ofNullable(accounts.get(event.originator().key())),
                beneficiary == null ? Optional.empty() : Optional.ofNullable(accounts.get(beneficiary.key())),
                beneficiary != null && knownBeneficiaries.contains(pair(event.originator(), beneficiary)),
                deviceId != null && knownDevices.contains(event.customerId() + '|' + deviceId));
    }

    public InMemoryProfileStore account(AccountRef account, AccountProfile profile) {
        accounts.put(account.key(), profile);
        return this;
    }

    /** Records that {@code originator} has paid {@code beneficiary} before. */
    public InMemoryProfileStore knownBeneficiary(AccountRef originator, AccountRef beneficiary) {
        knownBeneficiaries.add(pair(originator, beneficiary));
        return this;
    }

    /** Records that the customer has used the device before. */
    public InMemoryProfileStore knownDevice(String customerId, String deviceId) {
        knownDevices.add(customerId + '|' + deviceId);
        return this;
    }

    private static String pair(AccountRef originator, AccountRef beneficiary) {
        return originator.key() + '>' + beneficiary.key();
    }
}
