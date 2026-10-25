//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import vn.io.dxa.openframl.gate.Approval;
import vn.io.dxa.openframl.gate.ApprovedPolicy;
import vn.io.dxa.openframl.gate.PolicyChange;
import vn.io.dxa.openframl.gate.PolicyProposal;
import vn.io.dxa.openframl.gate.PolicyRepository;

/** Policies in memory: for tests and a single node. Everything is lost on restart. */
public final class InMemoryPolicyRepository implements PolicyRepository {

    private final Map<String, List<String>> tables = new HashMap<>();
    private final Map<String, PolicyProposal> proposals = new HashMap<>();
    private final List<ApprovedPolicy> history = new ArrayList<>();

    @Override
    public synchronized void storeTables(String policyId, List<String> tables) {
        this.tables.putIfAbsent(policyId, List.copyOf(tables));
    }

    @Override
    public synchronized List<String> tables(String policyId) {
        List<String> stored = tables.get(policyId);
        if (stored == null) {
            throw new NoSuchElementException("no policy " + policyId);
        }
        return stored;
    }

    @Override
    public synchronized void propose(PolicyProposal proposal) {
        if (proposals.putIfAbsent(proposal.id(), proposal) != null) {
            throw new IllegalStateException("proposal " + proposal.id() + " already exists");
        }
    }

    @Override
    public synchronized Optional<PolicyProposal> proposal(String id) {
        return Optional.ofNullable(proposals.get(id));
    }

    @Override
    public synchronized List<PolicyProposal> pending() {
        return proposals.values().stream().filter(p -> p.status() == PolicyProposal.Status.PENDING)
                .sorted(Comparator.comparing(PolicyProposal::proposedAt)).toList();
    }

    @Override
    public synchronized ApprovedPolicy approve(String proposalId, String checker, Instant at) {
        PolicyProposal proposal = pendingProposal(proposalId);
        Optional<ApprovedPolicy> active = active();
        if (active.isPresent() && !active.get().change().toId().equals(proposal.fromId())) {
            throw new IllegalStateException("proposal " + proposalId + " was made against " + proposal.fromId()
                    + " but " + active.get().change().toId() + " is now in force");
        }
        Approval approval = new Approval(proposal.maker(), checker, proposal.reason(), at);
        ApprovedPolicy approved = new ApprovedPolicy(history.size() + 1L,
                new PolicyChange(proposal.kind(), proposal.fromId(), proposal.toId(), approval));
        proposals.put(proposalId, decided(proposal, PolicyProposal.Status.APPROVED, checker, at, Optional.empty()));
        history.add(approved);
        return approved;
    }

    @Override
    public synchronized PolicyProposal reject(String proposalId, String checker, String reason, Instant at) {
        PolicyProposal rejected = decided(pendingProposal(proposalId), PolicyProposal.Status.REJECTED, checker, at,
                Optional.of(reason));
        proposals.put(proposalId, rejected);
        return rejected;
    }

    @Override
    public synchronized Optional<ApprovedPolicy> active() {
        return history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
    }

    @Override
    public synchronized List<ApprovedPolicy> history() {
        return List.copyOf(history);
    }

    /** Replaces a policy's stored tables, as someone with write access could; for tests of the hash check. */
    public synchronized void tamper(String policyId, List<String> tables) {
        this.tables.put(policyId, List.copyOf(tables));
    }

    private PolicyProposal pendingProposal(String id) {
        PolicyProposal proposal = proposals.get(id);
        if (proposal == null) {
            throw new NoSuchElementException("no proposal " + id);
        }
        if (proposal.status() != PolicyProposal.Status.PENDING) {
            throw new IllegalStateException("proposal " + id + " is " + proposal.status());
        }
        return proposal;
    }

    private static PolicyProposal decided(PolicyProposal p, PolicyProposal.Status status, String checker, Instant at,
            Optional<String> reason) {
        return new PolicyProposal(p.id(), p.kind(), p.fromId(), p.toId(), p.maker(), p.reason(), p.proposedAt(),
                status, Optional.of(checker), Optional.of(at), reason);
    }
}
