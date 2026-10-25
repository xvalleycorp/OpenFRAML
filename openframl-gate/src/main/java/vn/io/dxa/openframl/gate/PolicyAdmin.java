//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.core.DecisionTableJson;

/**
 * Four-eyes changes of the policy every gate node runs. A maker proposes a policy; a different person, a checker,
 * approves it after reviewing exactly that policy; every node then puts it in force through its {@link PolicySync}.
 *
 * <p>A proposal is compiled and checked when it is made, so a checker never reviews a policy that cannot run. It is
 * made against the policy in force at the time and can only be approved while that policy is still in force: two
 * changes prepared in parallel cannot both be approved without the second being reviewed again.
 *
 * <p>Who the caller is comes from the transport (the admin service takes it from the client certificate); this class
 * checks what the caller may do.
 */
public final class PolicyAdmin {

    public enum Role {
        /** May propose changes. */
        MAKER,
        /** May approve or reject them, but never their own. */
        CHECKER
    }

    /**
     * An authenticated caller.
     *
     * @param name  the identity, e.g. the client certificate's common name
     * @param roles what the bank's configuration allows it
     */
    public record Caller(String name, Set<Role> roles) {

        public Caller {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            name = name.strip();
            roles = Set.copyOf(roles);
        }
    }

    private final CountryPack pack;
    private final PolicyRepository repository;
    private final GatePolicy initial;
    private final Clock clock;

    /**
     * @param initial the policy the gates started with, before any approved change; its tables are kept in the
     *                repository so that a rollback can return to it
     */
    public PolicyAdmin(CountryPack pack, PolicyRepository repository, GatePolicy initial, Clock clock) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.initial = Objects.requireNonNull(initial, "initial");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Proposes a policy made of {@code tables}, RuleStone decision tables as JSON, every version included.
     *
     * @throws SecurityException        if the caller is not a maker
     * @throws IllegalArgumentException if the tables do not compile, or the policy is already in force
     */
    public PolicyProposal propose(Caller maker, List<String> tables, String reason) {
        require(maker, Role.MAKER);
        requireReason(reason);
        GatePolicy policy;
        try {
            policy = GatePolicy.compile(pack, tables.stream().map(DecisionTableJson::read).toList());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the tables do not compile: " + e.getMessage(), e);
        }
        String current = currentId();
        if (policy.id().equals(current)) {
            throw new IllegalArgumentException("policy " + policy.id() + " is already in force");
        }
        keepInitial();
        repository.storeTables(policy.id(), json(policy));
        return newProposal(PolicyChange.Kind.ACTIVATE, current, policy.id(), maker, reason);
    }

    /**
     * Proposes going back to the policy in force before the last approved change.
     *
     * @throws SecurityException     if the caller is not a maker
     * @throws IllegalStateException if no change has been approved yet
     */
    public PolicyProposal proposeRollback(Caller maker, String reason) {
        require(maker, Role.MAKER);
        requireReason(reason);
        ApprovedPolicy active = repository.active()
                .orElseThrow(() -> new IllegalStateException("no approved change to roll back"));
        return newProposal(PolicyChange.Kind.ROLLBACK, active.change().toId(), active.change().fromId(), maker,
                reason);
    }

    /**
     * Approves a proposal. The checker names the policy they reviewed, so that what is approved is what was seen.
     *
     * @throws SecurityException        if the caller is not a checker, or is the proposal's maker
     * @throws IllegalArgumentException if {@code reviewedPolicyId} is not the proposal's policy
     * @throws IllegalStateException    if the proposal is not pending, or the policy it was made against is no
     *                                  longer in force
     */
    public ApprovedPolicy approve(Caller checker, String proposalId, String reviewedPolicyId) {
        PolicyProposal proposal = decidable(checker, proposalId);
        if (!proposal.toId().equals(reviewedPolicyId)) {
            throw new IllegalArgumentException("proposal " + proposalId + " puts policy " + proposal.toId()
                    + " in force, not " + reviewedPolicyId);
        }
        return repository.approve(proposalId, checker.name(), clock.instant());
    }

    /**
     * @throws SecurityException     if the caller is not a checker, or is the proposal's maker
     * @throws IllegalStateException if the proposal is not pending
     */
    public PolicyProposal reject(Caller checker, String proposalId, String reason) {
        decidable(checker, proposalId);
        requireReason(reason);
        return repository.reject(proposalId, checker.name(), reason, clock.instant());
    }

    public List<PolicyProposal> pending() {
        return repository.pending();
    }

    public Optional<ApprovedPolicy> active() {
        return repository.active();
    }

    public List<ApprovedPolicy> history() {
        return repository.history();
    }

    /** The tables of a stored policy, for the checker to review. */
    public List<String> tables(String policyId) {
        return repository.tables(policyId);
    }

    /** The policy every node must run: the last approved one, or the one the gates started with. */
    public String currentId() {
        return repository.active().map(active -> active.change().toId()).orElse(initial.id());
    }

    private PolicyProposal decidable(Caller checker, String proposalId) {
        require(checker, Role.CHECKER);
        PolicyProposal proposal = repository.proposal(proposalId)
                .orElseThrow(() -> new NoSuchElementException("no proposal " + proposalId));
        if (proposal.maker().equalsIgnoreCase(checker.name())) {
            throw new SecurityException(checker.name() + " proposed " + proposalId + " and cannot also decide it");
        }
        return proposal;
    }

    private PolicyProposal newProposal(PolicyChange.Kind kind, String fromId, String toId, Caller maker,
            String reason) {
        PolicyProposal proposal = PolicyProposal.pending(UUID.randomUUID().toString(), kind, fromId, toId,
                maker.name(), reason.strip(), clock.instant());
        repository.propose(proposal);
        return proposal;
    }

    private void keepInitial() {
        if (repository.active().isEmpty()) {
            repository.storeTables(initial.id(), json(initial));
        }
    }

    private static List<String> json(GatePolicy policy) {
        return policy.tables().stream().map(DecisionTableJson::write).toList();
    }

    private static void require(Caller caller, Role role) {
        Objects.requireNonNull(caller, "caller");
        if (!caller.roles().contains(role)) {
            throw new SecurityException(caller.name() + " is not a " + role.name().toLowerCase());
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required, e.g. the change ticket");
        }
    }
}
