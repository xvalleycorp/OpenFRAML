//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.valkey;

import java.util.Objects;
import vn.io.dxa.openframl.model.AccountRef;

/**
 * Where the gate's data lives in Valkey. The stream layer (or a batch job) writes profiles in this layout; the gate
 * reads them. All keys start with a prefix, {@code ofr:} by default, so one Valkey can serve several environments.
 *
 * <table>
 *   <caption>Keys</caption>
 *   <tr><th>Key</th><th>Type</th><th>Content</th></tr>
 *   <tr><td>{@code ofr:acct:<account>}</td><td>hash</td><td>{@code openedOn} (ISO date), {@code risk} (rating)</td></tr>
 *   <tr><td>{@code ofr:payees:<account>}</td><td>set</td><td>beneficiary accounts the account has paid before</td></tr>
 *   <tr><td>{@code ofr:devices:<customer>}</td><td>set</td><td>devices the customer has used before</td></tr>
 *   <tr><td>{@code ofr:vel:{<account>}}</td><td>sorted set</td><td>payment attempts of the last 24 hours, scored
 *       by time in milliseconds</td></tr>
 *   <tr><td>{@code ofr:{policy}:tables:<policy id>}</td><td>string</td><td>a policy's tables, a JSON array of
 *       RuleStone tables</td></tr>
 *   <tr><td>{@code ofr:{policy}:proposal:<id>}</td><td>hash</td><td>a proposed change and its decision</td></tr>
 *   <tr><td>{@code ofr:{policy}:pending}</td><td>sorted set</td><td>pending proposals, scored by time</td></tr>
 *   <tr><td>{@code ofr:{policy}:active}</td><td>hash</td><td>the last approved change, which every node runs</td></tr>
 *   <tr><td>{@code ofr:{policy}:history}</td><td>list</td><td>approved proposals, oldest first</td></tr>
 * </table>
 *
 * <p>The policy keys share the hash tag {@code {policy}}, so a cluster keeps them on one node and an approval can
 * update them in one atomic step.
 *
 * <p>{@code <account>} is {@link AccountRef#key()}.
 */
public record ValkeyLayout(String prefix) {

    public static final ValkeyLayout DEFAULT = new ValkeyLayout("ofr:");

    /** Field of the account hash: the day the account was opened, e.g. {@code 2020-01-15}. */
    public static final String OPENED_ON = "openedOn";
    /** Field of the account hash: the customer's AML risk rating; absent if unrated. */
    public static final String RISK = "risk";

    public ValkeyLayout {
        Objects.requireNonNull(prefix, "prefix");
    }

    public String account(AccountRef account) {
        return prefix + "acct:" + account.key();
    }

    public String payees(AccountRef originator) {
        return prefix + "payees:" + originator.key();
    }

    public String devices(String customerId) {
        return prefix + "devices:" + customerId;
    }

    public String policyTables(String policyId) {
        return prefix + "{policy}:tables:" + policyId;
    }

    public String proposal(String proposalId) {
        return prefix + "{policy}:proposal:" + proposalId;
    }

    public String pendingProposals() {
        return prefix + "{policy}:pending";
    }

    public String activePolicy() {
        return prefix + "{policy}:active";
    }

    public String policyHistory() {
        return prefix + "{policy}:history";
    }

    /** One key per account, with a hash tag so that a cluster keeps it on one node. */
    public String velocity(AccountRef originator) {
        return prefix + "vel:{" + originator.key() + "}";
    }
}
