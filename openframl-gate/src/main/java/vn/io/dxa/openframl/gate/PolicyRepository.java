//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where proposals, approvals and the tables of every policy are kept, shared by every gate node: the admin service
 * writes to it, and each node's {@link PolicySync} reads the approved policy from it.
 *
 * <p>Whoever can write to the repository can change what the gates run, so access to it must be restricted like
 * access to the admin service itself.
 */
public interface PolicyRepository {

    /** Keeps the tables of a policy, as RuleStone JSON; storing the same policy again changes nothing. */
    void storeTables(String policyId, List<String> tables);

    /**
     * @throws java.util.NoSuchElementException if the policy was never stored
     */
    List<String> tables(String policyId);

    void propose(PolicyProposal proposal);

    Optional<PolicyProposal> proposal(String id);

    /** Pending proposals, oldest first. */
    List<PolicyProposal> pending();

    /**
     * In one atomic step: marks the proposal approved and makes its policy the one every node must run.
     *
     * @throws java.util.NoSuchElementException if there is no such proposal
     * @throws IllegalStateException            if it is not pending, or the policy in force is no longer the one it
     *                                          was proposed against
     * @throws IllegalArgumentException         if the checker is the maker
     */
    ApprovedPolicy approve(String proposalId, String checker, Instant at);

    /**
     * @throws java.util.NoSuchElementException if there is no such proposal
     * @throws IllegalStateException            if it is not pending
     */
    PolicyProposal reject(String proposalId, String checker, String reason, Instant at);

    /** The last approved change, if there has been one. */
    Optional<ApprovedPolicy> active();

    /** Every approved change, oldest first. */
    List<ApprovedPolicy> history();
}
