//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import vn.io.dxa.openframl.gate.Approval;
import vn.io.dxa.openframl.gate.ApprovedPolicy;
import vn.io.dxa.openframl.gate.PolicyChange;
import vn.io.dxa.openframl.gate.PolicyProposal;
import vn.io.dxa.openframl.gate.PolicyRepository;

/**
 * Policies, proposals and approvals on Valkey, shared by every gate node and the admin service, in the
 * {@link ValkeyLayout}. Approving and rejecting are scripts, so a proposal is decided once and an approval made
 * against a policy no longer in force is refused, even with several admin instances.
 *
 * <p>Restrict who can write these keys with Valkey ACLs: the nodes check that the tables hash to the approved policy
 * id, but they cannot tell an approval written by the admin service from one written by hand.
 */
public final class ValkeyPolicyRepository implements PolicyRepository {

    private static final LuaScript APPROVE = LuaScript.load("approve-proposal.lua");
    private static final LuaScript REJECT = LuaScript.load("reject-proposal.lua");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RedisAsyncCommands<String, String> commands;
    private final ValkeyLayout layout;

    public ValkeyPolicyRepository(Valkey valkey) {
        this(valkey.connection(), ValkeyLayout.DEFAULT);
    }

    public ValkeyPolicyRepository(StatefulRedisConnection<String, String> connection, ValkeyLayout layout) {
        this.commands = connection.async();
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    @Override
    public void storeTables(String policyId, List<String> tables) {
        try {
            LuaScript.await(commands.set(layout.policyTables(policyId), JSON.writeValueAsString(tables),
                    SetArgs.Builder.nx()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<String> tables(String policyId) {
        String stored = LuaScript.await(commands.get(layout.policyTables(policyId)));
        if (stored == null) {
            throw new NoSuchElementException("no policy " + policyId);
        }
        try {
            return JSON.readValue(stored, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("policy " + policyId + " is not stored as a JSON array", e);
        }
    }

    @Override
    public void propose(PolicyProposal proposal) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", proposal.status().name());
        fields.put("kind", proposal.kind().name());
        fields.put("fromId", proposal.fromId());
        fields.put("toId", proposal.toId());
        fields.put("maker", proposal.maker());
        fields.put("reason", proposal.reason());
        fields.put("proposedAt", proposal.proposedAt().toString());
        String key = layout.proposal(proposal.id());
        if (!LuaScript.await(commands.hsetnx(key, "id", proposal.id()))) {
            throw new IllegalStateException("proposal " + proposal.id() + " already exists");
        }
        LuaScript.await(commands.hset(key, fields));
        LuaScript.await(commands.zadd(layout.pendingProposals(), proposal.proposedAt().toEpochMilli(),
                proposal.id()));
    }

    @Override
    public Optional<PolicyProposal> proposal(String id) {
        Map<String, String> fields = LuaScript.await(commands.hgetall(layout.proposal(id)));
        return fields.isEmpty() || !fields.containsKey("status") ? Optional.empty() : Optional.of(proposal(id, fields));
    }

    @Override
    public List<PolicyProposal> pending() {
        List<PolicyProposal> pending = new ArrayList<>();
        for (String id : LuaScript.await(commands.zrange(layout.pendingProposals(), 0, -1))) {
            proposal(id).ifPresent(pending::add);
        }
        return pending;
    }

    @Override
    public ApprovedPolicy approve(String proposalId, String checker, Instant at) {
        String[] keys = {layout.proposal(proposalId), layout.pendingProposals(), layout.activePolicy(),
                layout.policyHistory()};
        try {
            APPROVE.<Long>run(commands, ScriptOutputType.INTEGER, keys, proposalId, checker, at.toString());
        } catch (RedisCommandExecutionException e) {
            throw translate(e);
        }
        return active().orElseThrow();
    }

    @Override
    public PolicyProposal reject(String proposalId, String checker, String reason, Instant at) {
        String[] keys = {layout.proposal(proposalId), layout.pendingProposals()};
        try {
            REJECT.<Long>run(commands, ScriptOutputType.INTEGER, keys, proposalId, checker, at.toString(), reason);
        } catch (RedisCommandExecutionException e) {
            throw translate(e);
        }
        return proposal(proposalId).orElseThrow();
    }

    @Override
    public Optional<ApprovedPolicy> active() {
        Map<String, String> fields = LuaScript.await(commands.hgetall(layout.activePolicy()));
        if (!fields.containsKey("toId")) {
            return Optional.empty();
        }
        return Optional.of(new ApprovedPolicy(Long.parseLong(fields.get("seq")), new PolicyChange(
                PolicyChange.Kind.valueOf(fields.get("kind")), fields.get("fromId"), fields.get("toId"),
                new Approval(fields.get("maker"), fields.get("checker"), fields.get("reason"),
                        Instant.parse(fields.get("approvedAt"))))));
    }

    @Override
    public List<ApprovedPolicy> history() {
        List<ApprovedPolicy> history = new ArrayList<>();
        for (String id : LuaScript.await(commands.lrange(layout.policyHistory(), 0, -1))) {
            Map<String, String> fields = LuaScript.await(commands.hgetall(layout.proposal(id)));
            PolicyProposal p = proposal(id, fields);
            history.add(new ApprovedPolicy(Long.parseLong(fields.get("seq")),
                    new PolicyChange(p.kind(), p.fromId(), p.toId(), new Approval(p.maker(), p.decidedBy().orElseThrow(),
                            p.reason(), p.decidedAt().orElseThrow()))));
        }
        return history;
    }

    private static PolicyProposal proposal(String id, Map<String, String> fields) {
        return new PolicyProposal(id, PolicyChange.Kind.valueOf(fields.get("kind")), fields.get("fromId"),
                fields.get("toId"), fields.get("maker"), fields.get("reason"), Instant.parse(fields.get("proposedAt")),
                PolicyProposal.Status.valueOf(fields.get("status")), Optional.ofNullable(fields.get("decidedBy")),
                Optional.ofNullable(fields.get("decidedAt")).map(Instant::parse),
                Optional.ofNullable(fields.get("decisionReason")));
    }

    /** The scripts' error codes, as the exceptions {@link PolicyRepository} documents. */
    private static RuntimeException translate(RedisCommandExecutionException e) {
        String message = Objects.requireNonNullElse(e.getMessage(), "");
        String detail = message.contains(" ") ? message.substring(message.indexOf(' ') + 1) : message;
        if (message.startsWith("NOTFOUND")) {
            return new NoSuchElementException(detail);
        }
        if (message.startsWith("NOTPENDING") || message.startsWith("STALE")) {
            return new IllegalStateException(detail);
        }
        if (message.startsWith("SAMEPERSON")) {
            return new IllegalArgumentException(detail);
        }
        return e;
    }
}
