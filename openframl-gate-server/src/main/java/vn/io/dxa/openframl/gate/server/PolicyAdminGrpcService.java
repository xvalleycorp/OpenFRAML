//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.server;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;
import vn.io.dxa.openframl.gate.ApprovedPolicy;
import vn.io.dxa.openframl.gate.PolicyAdmin;
import vn.io.dxa.openframl.gate.PolicyAdmin.Caller;
import vn.io.dxa.openframl.gate.PolicyProposal;
import vn.io.dxa.openframl.gate.admin.v1.ApproveRequest;
import vn.io.dxa.openframl.gate.admin.v1.ChangeKind;
import vn.io.dxa.openframl.gate.admin.v1.GetActiveRequest;
import vn.io.dxa.openframl.gate.admin.v1.GetActiveResponse;
import vn.io.dxa.openframl.gate.admin.v1.GetPolicyTablesRequest;
import vn.io.dxa.openframl.gate.admin.v1.GetPolicyTablesResponse;
import vn.io.dxa.openframl.gate.admin.v1.ListHistoryRequest;
import vn.io.dxa.openframl.gate.admin.v1.ListHistoryResponse;
import vn.io.dxa.openframl.gate.admin.v1.ListPendingRequest;
import vn.io.dxa.openframl.gate.admin.v1.ListPendingResponse;
import vn.io.dxa.openframl.gate.admin.v1.PolicyAdminGrpc;
import vn.io.dxa.openframl.gate.admin.v1.PolicyChange;
import vn.io.dxa.openframl.gate.admin.v1.Proposal;
import vn.io.dxa.openframl.gate.admin.v1.ProposalStatus;
import vn.io.dxa.openframl.gate.admin.v1.ProposeRequest;
import vn.io.dxa.openframl.gate.admin.v1.ProposeRollbackRequest;
import vn.io.dxa.openframl.gate.admin.v1.RejectRequest;

/**
 * {@code openframl.gate.admin.v1.PolicyAdmin} on top of a {@link PolicyAdmin}. Serve it behind
 * {@link AdminAuthentication}, which says who the caller is.
 */
public final class PolicyAdminGrpcService extends PolicyAdminGrpc.PolicyAdminImplBase {

    private static final System.Logger LOG = System.getLogger(PolicyAdminGrpcService.class.getName());

    private final PolicyAdmin admin;

    public PolicyAdminGrpcService(PolicyAdmin admin) {
        this.admin = Objects.requireNonNull(admin, "admin");
    }

    @Override
    public void propose(ProposeRequest request, StreamObserver<Proposal> responses) {
        respond(responses, () -> {
            PolicyProposal proposal = admin.propose(caller(), request.getTablesList(), request.getReason());
            LOG.log(INFO, "{0} proposed {1}: policy {2}", proposal.maker(), proposal.id(), proposal.toId());
            return proposal(proposal);
        });
    }

    @Override
    public void proposeRollback(ProposeRollbackRequest request, StreamObserver<Proposal> responses) {
        respond(responses, () -> {
            PolicyProposal proposal = admin.proposeRollback(caller(), request.getReason());
            LOG.log(INFO, "{0} proposed {1}: roll back to policy {2}", proposal.maker(), proposal.id(),
                    proposal.toId());
            return proposal(proposal);
        });
    }

    @Override
    public void approve(ApproveRequest request, StreamObserver<PolicyChange> responses) {
        respond(responses, () -> {
            ApprovedPolicy approved = admin.approve(caller(), request.getProposalId(), request.getPolicyId());
            LOG.log(INFO, "{0} approved {1}: policy {2} is change {3}", approved.change().approval().checker(),
                    request.getProposalId(), approved.change().toId(), approved.sequence());
            return change(approved);
        });
    }

    @Override
    public void reject(RejectRequest request, StreamObserver<Proposal> responses) {
        respond(responses, () -> {
            PolicyProposal proposal = admin.reject(caller(), request.getProposalId(), request.getReason());
            LOG.log(INFO, "{0} rejected {1}", proposal.decidedBy().orElseThrow(), proposal.id());
            return proposal(proposal);
        });
    }

    @Override
    public void listPending(ListPendingRequest request, StreamObserver<ListPendingResponse> responses) {
        respond(responses, () -> {
            caller();
            ListPendingResponse.Builder response = ListPendingResponse.newBuilder();
            admin.pending().forEach(p -> response.addProposals(proposal(p)));
            return response.build();
        });
    }

    @Override
    public void getPolicyTables(GetPolicyTablesRequest request, StreamObserver<GetPolicyTablesResponse> responses) {
        respond(responses, () -> {
            caller();
            return GetPolicyTablesResponse.newBuilder().addAllTables(admin.tables(request.getPolicyId())).build();
        });
    }

    @Override
    public void getActive(GetActiveRequest request, StreamObserver<GetActiveResponse> responses) {
        respond(responses, () -> {
            caller();
            GetActiveResponse.Builder response = GetActiveResponse.newBuilder().setPolicyId(admin.currentId());
            admin.active().ifPresent(active -> response.setChange(change(active)));
            return response.build();
        });
    }

    @Override
    public void listHistory(ListHistoryRequest request, StreamObserver<ListHistoryResponse> responses) {
        respond(responses, () -> {
            caller();
            ListHistoryResponse.Builder response = ListHistoryResponse.newBuilder();
            admin.history().forEach(change -> response.addChanges(change(change)));
            return response.build();
        });
    }

    /** The authenticated caller; every method, reads included, needs one. */
    private static Caller caller() {
        Caller caller = AdminAuthentication.CALLER.get();
        if (caller == null) {
            throw new SecurityException("unauthenticated");
        }
        if (caller.roles().isEmpty()) {
            throw new SecurityException(caller.name() + " is neither a maker nor a checker");
        }
        return caller;
    }

    private static <T> void respond(StreamObserver<T> responses, Supplier<T> call) {
        T response;
        try {
            response = call.get();
        } catch (SecurityException e) {
            LOG.log(WARNING, "admin call refused: {0}", e.getMessage());
            responses.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (NoSuchElementException e) {
            responses.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (IllegalStateException e) {
            responses.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (IllegalArgumentException e) {
            responses.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        responses.onNext(response);
        responses.onCompleted();
    }

    private static Proposal proposal(PolicyProposal p) {
        Proposal.Builder proposal = Proposal.newBuilder().setId(p.id()).setKind(kind(p.kind()))
                .setFromPolicyId(p.fromId()).setToPolicyId(p.toId()).setMaker(p.maker()).setReason(p.reason())
                .setProposedAt(timestamp(p.proposedAt())).setStatus(switch (p.status()) {
                    case PENDING -> ProposalStatus.PROPOSAL_STATUS_PENDING;
                    case APPROVED -> ProposalStatus.PROPOSAL_STATUS_APPROVED;
                    case REJECTED -> ProposalStatus.PROPOSAL_STATUS_REJECTED;
                });
        p.decidedBy().ifPresent(proposal::setDecidedBy);
        p.decidedAt().ifPresent(at -> proposal.setDecidedAt(timestamp(at)));
        p.decisionReason().ifPresent(proposal::setDecisionReason);
        return proposal.build();
    }

    private static PolicyChange change(ApprovedPolicy approved) {
        vn.io.dxa.openframl.gate.PolicyChange c = approved.change();
        return PolicyChange.newBuilder().setSequence(approved.sequence()).setKind(kind(c.kind()))
                .setFromPolicyId(c.fromId()).setToPolicyId(c.toId()).setMaker(c.approval().maker())
                .setChecker(c.approval().checker()).setReason(c.approval().reason())
                .setApprovedAt(timestamp(c.approval().approvedAt())).build();
    }

    private static ChangeKind kind(vn.io.dxa.openframl.gate.PolicyChange.Kind kind) {
        return switch (kind) {
            case ACTIVATE -> ChangeKind.CHANGE_KIND_ACTIVATE;
            case ROLLBACK -> ChangeKind.CHANGE_KIND_ROLLBACK;
        };
    }

    private static Timestamp timestamp(Instant at) {
        return Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()).build();
    }
}
