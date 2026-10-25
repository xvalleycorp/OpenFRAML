//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.core.DecisionTableJson;

/**
 * Keeps one gate node on the approved policy: reads the last approved change from the {@link PolicyRepository} and,
 * when it is not in force here, compiles its tables and puts it in force.
 *
 * <p>The node trusts the tables only if they hash to the approved policy id: tables changed in the repository after
 * approval are refused, and the node stays on the policy it has. A change that fails on this node is logged once and
 * not retried; the next approved change is tried as usual.
 */
public final class PolicySync implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PolicySync.class.getName());

    private final Gate gate;
    private final CountryPack pack;
    private final PolicyRepository repository;
    private ScheduledExecutorService scheduler;
    private long applied;
    private long failed;

    public PolicySync(Gate gate, CountryPack pack, PolicyRepository repository) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.pack = Objects.requireNonNull(pack, "pack");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Syncs now, so the node starts on the approved policy, then every {@code interval}.
     */
    public static PolicySync start(Gate gate, CountryPack pack, PolicyRepository repository, Duration interval) {
        PolicySync sync = new PolicySync(gate, pack, repository);
        sync.sync();
        sync.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("openframl-policy-sync").daemon().factory());
        sync.scheduler.scheduleWithFixedDelay(sync::sync, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
        return sync;
    }

    /**
     * Puts the approved policy in force if it is not already.
     *
     * @return the change made on this node, if one was
     */
    public synchronized Optional<PolicyChange> sync() {
        Optional<ApprovedPolicy> approved;
        try {
            approved = repository.active();
        } catch (RuntimeException e) {
            LOG.log(WARNING, "cannot read the approved policy: {0}", e.toString());
            return Optional.empty();
        }
        if (approved.isEmpty() || approved.get().sequence() <= applied || approved.get().sequence() == failed) {
            return Optional.empty();
        }
        ApprovedPolicy active = approved.get();
        PolicyChange change = active.change();
        if (gate.policy().id().equals(change.toId())) {
            applied = active.sequence();
            return Optional.empty();
        }
        try {
            GatePolicy policy = GatePolicy.compile(pack,
                    repository.tables(change.toId()).stream().map(DecisionTableJson::read).toList());
            if (!policy.id().equals(change.toId())) {
                throw new IllegalStateException("the stored tables hash to " + policy.id() + ", not to the approved "
                        + change.toId());
            }
            PolicyChange made = gate.apply(policy, change.kind(), change.approval());
            applied = active.sequence();
            return Optional.of(made);
        } catch (RuntimeException e) {
            failed = active.sequence();
            LOG.log(ERROR, "approved change {0} to policy {1} not applied on this node: {2}", active.sequence(),
                    change.toId(), e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
