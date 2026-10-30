//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.monitor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Each account's recent movements, and when it was last active. Monitoring of one account must run in order, one
 * transaction at a time (the stream layer partitions by account); different accounts run in parallel.
 */
public interface ActivityStore {

    /** When the account last moved money, if ever. */
    Optional<Instant> lastActivity(String account);

    /**
     * Records a movement of the account.
     *
     * @return {@code false} if the event was already recorded, so a transaction delivered twice is counted once
     */
    boolean add(String account, Movement movement);

    /** The account's movements at or after {@code since}, oldest first. */
    List<Movement> since(String account, Instant since);
}
