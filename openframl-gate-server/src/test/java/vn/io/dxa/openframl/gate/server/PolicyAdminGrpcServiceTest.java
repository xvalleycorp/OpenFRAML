//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.io.dxa.openframl.gate.Gate;
import vn.io.dxa.openframl.gate.GatePolicy;
import vn.io.dxa.openframl.gate.PolicyAdmin;
import vn.io.dxa.openframl.gate.PolicySync;
import vn.io.dxa.openframl.gate.admin.v1.ApproveRequest;
import vn.io.dxa.openframl.gate.admin.v1.ChangeKind;
import vn.io.dxa.openframl.gate.admin.v1.GetActiveRequest;
import vn.io.dxa.openframl.gate.admin.v1.GetPolicyTablesRequest;
import vn.io.dxa.openframl.gate.admin.v1.ListHistoryRequest;
import vn.io.dxa.openframl.gate.admin.v1.ListPendingRequest;
import vn.io.dxa.openframl.gate.admin.v1.PolicyAdminGrpc;
import vn.io.dxa.openframl.gate.admin.v1.PolicyAdminGrpc.PolicyAdminBlockingStub;
import vn.io.dxa.openframl.gate.admin.v1.PolicyChange;
import vn.io.dxa.openframl.gate.admin.v1.Proposal;
import vn.io.dxa.openframl.gate.admin.v1.ProposalStatus;
import vn.io.dxa.openframl.gate.admin.v1.ProposeRequest;
import vn.io.dxa.openframl.gate.admin.v1.ProposeRollbackRequest;
import vn.io.dxa.openframl.gate.admin.v1.RejectRequest;
import vn.io.dxa.openframl.gate.memory.InMemoryPolicyRepository;
import vn.io.dxa.openframl.gate.memory.InMemoryProfileStore;
import vn.io.dxa.openframl.gate.memory.InMemoryVelocityStore;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.core.DecisionTableJson;

/**
 * The admin service in process. The caller's name comes from a test header instead of a certificate; the mutual TLS
 * path is covered by {@link AdminMutualTlsTest}.
 */
class PolicyAdminGrpcServiceTest {

    private static final Metadata.Key<String> AS = Metadata.Key.of("test-caller", Metadata.ASCII_STRING_MARSHALLER);

    private final CountryPack pack = CountryPack.forCountry("VN");
    private final GatePolicy initial = GatePolicy.of(pack);
    private final InMemoryPolicyRepository repository = new InMemoryPolicyRepository();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        AdminAuthentication authentication = new AdminAuthentication(Set.of("an.maker", "chi"),
                Set.of("binh.checker", "chi"), call -> Optional.ofNullable(TestCaller.NAME.get()));
        server = InProcessServerBuilder.forName(name).directExecutor()
                .intercept(new TestCaller(AS))
                .addService(ServerInterceptors.intercept(new PolicyAdminGrpcService(
                        new PolicyAdmin(pack, repository, initial, Clock.systemUTC())), authentication))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stop() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private PolicyAdminBlockingStub as(String caller) {
        Metadata headers = new Metadata();
        if (caller != null) {
            headers.put(AS, caller);
        }
        return PolicyAdminGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private List<String> tables(String threshold) {
        return initial.tables().stream()
                .map(table -> DecisionTableJson.write(table).replace("money(20000000, 'VND')", threshold)).toList();
    }

    private static Status.Code code(Runnable call) {
        return assertThrows(StatusRuntimeException.class, call::run).getStatus().getCode();
    }

    @Test
    void aMakerProposesACheckerReviewsAndApprovesAndTheNodeFollows() {
        Proposal proposal = as("an.maker").propose(ProposeRequest.newBuilder()
                .addAllTables(tables("money(15000000, 'VND')")).setReason("CHG-1024").build());
        assertEquals(ProposalStatus.PROPOSAL_STATUS_PENDING, proposal.getStatus());
        assertEquals(initial.id(), proposal.getFromPolicyId());

        PolicyAdminBlockingStub binh = as("binh.checker");
        assertEquals(List.of(proposal), binh.listPending(ListPendingRequest.getDefaultInstance()).getProposalsList());
        List<String> reviewed = binh.getPolicyTables(GetPolicyTablesRequest.newBuilder()
                .setPolicyId(proposal.getToPolicyId()).build()).getTablesList();
        assertEquals(tables("money(15000000, 'VND')"), reviewed);

        PolicyChange change = binh.approve(ApproveRequest.newBuilder().setProposalId(proposal.getId())
                .setPolicyId(proposal.getToPolicyId()).build());
        assertEquals(1, change.getSequence());
        assertEquals("an.maker", change.getMaker());
        assertEquals("binh.checker", change.getChecker());
        assertEquals(proposal.getToPolicyId(), binh.getActive(GetActiveRequest.getDefaultInstance()).getPolicyId());

        try (Gate gate = Gate.builder(pack).profiles(new InMemoryProfileStore())
                .velocity(new InMemoryVelocityStore()).build()) {
            new PolicySync(gate, pack, repository).sync();
            assertEquals(proposal.getToPolicyId(), gate.policy().id());
        }
    }

    @Test
    void eachRefusalHasItsStatus() {
        Proposal proposal = as("chi").propose(ProposeRequest.newBuilder()
                .addAllTables(tables("money(15000000, 'VND')")).setReason("CHG-1").build());
        ApproveRequest approve = ApproveRequest.newBuilder().setProposalId(proposal.getId())
                .setPolicyId(proposal.getToPolicyId()).build();

        assertEquals(Status.Code.UNAUTHENTICATED, code(() -> as(null).approve(approve)));
        assertEquals(Status.Code.PERMISSION_DENIED, code(() -> as("eve").listPending(
                ListPendingRequest.getDefaultInstance())));
        assertEquals(Status.Code.PERMISSION_DENIED, code(() -> as("chi").approve(approve)));
        assertEquals(Status.Code.PERMISSION_DENIED, code(() -> as("an.maker").approve(approve)));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> as("binh.checker").approve(approve.toBuilder()
                .setPolicyId(initial.id()).build())));
        assertEquals(Status.Code.NOT_FOUND, code(() -> as("binh.checker").approve(approve.toBuilder()
                .setProposalId("nope").build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> as("an.maker").propose(ProposeRequest.newBuilder()
                .addTables("{\"not\": \"a table\"}").setReason("CHG-2").build())));
        assertEquals(Status.Code.FAILED_PRECONDITION, code(() -> as("an.maker").proposeRollback(
                ProposeRollbackRequest.newBuilder().setReason("INC-1").build())));

        as("binh.checker").reject(RejectRequest.newBuilder().setProposalId(proposal.getId())
                .setReason("needs a backtest").build());
        assertEquals(Status.Code.FAILED_PRECONDITION, code(() -> as("binh.checker").approve(approve)));
    }

    @Test
    void aRollbackIsProposedAndApprovedLikeAnyChange() {
        Proposal proposal = as("an.maker").propose(ProposeRequest.newBuilder()
                .addAllTables(tables("money(15000000, 'VND')")).setReason("CHG-1").build());
        as("binh.checker").approve(ApproveRequest.newBuilder().setProposalId(proposal.getId())
                .setPolicyId(proposal.getToPolicyId()).build());

        Proposal back = as("an.maker").proposeRollback(ProposeRollbackRequest.newBuilder().setReason("INC-7").build());
        assertEquals(ChangeKind.CHANGE_KIND_ROLLBACK, back.getKind());
        as("binh.checker").approve(ApproveRequest.newBuilder().setProposalId(back.getId())
                .setPolicyId(initial.id()).build());

        List<PolicyChange> history = as("binh.checker").listHistory(ListHistoryRequest.getDefaultInstance())
                .getChangesList();
        assertEquals(2, history.size());
        assertEquals(initial.id(), history.getLast().getToPolicyId());
        assertFalse(history.getLast().getReason().isEmpty());
    }
}
