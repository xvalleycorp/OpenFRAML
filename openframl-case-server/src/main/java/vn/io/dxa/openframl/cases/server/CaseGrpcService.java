//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases.server;

import static java.lang.System.Logger.Level.WARNING;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import vn.io.dxa.openframl.cases.Alert;
import vn.io.dxa.openframl.cases.CaseFile;
import vn.io.dxa.openframl.cases.CaseService;
import vn.io.dxa.openframl.cases.CaseService.Investigator;
import vn.io.dxa.openframl.cases.v1.AddNoteRequest;
import vn.io.dxa.openframl.cases.v1.ApproveConclusionRequest;
import vn.io.dxa.openframl.cases.v1.AssignRequest;
import vn.io.dxa.openframl.cases.v1.Case;
import vn.io.dxa.openframl.cases.v1.CaseServiceGrpc;
import vn.io.dxa.openframl.cases.v1.CaseStatus;
import vn.io.dxa.openframl.cases.v1.GetCaseRequest;
import vn.io.dxa.openframl.cases.v1.ListCasesRequest;
import vn.io.dxa.openframl.cases.v1.ListCasesResponse;
import vn.io.dxa.openframl.cases.v1.Outcome;
import vn.io.dxa.openframl.cases.v1.ProposeConclusionRequest;
import vn.io.dxa.openframl.cases.v1.RaiseAlertRequest;
import vn.io.dxa.openframl.cases.v1.ReturnConclusionRequest;

/**
 * {@code openframl.cases.v1.CaseService} on top of a {@link CaseService}. Serve it behind
 * {@link CaseAuthentication}, which says who the caller is; this class checks the caller against the
 * {@link CaseDirectory}.
 */
public final class CaseGrpcService extends CaseServiceGrpc.CaseServiceImplBase {

    private static final System.Logger LOG = System.getLogger(CaseGrpcService.class.getName());

    private final CaseService cases;
    private final CaseDirectory directory;

    public CaseGrpcService(CaseService cases, CaseDirectory directory) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public void raiseAlert(RaiseAlertRequest request, StreamObserver<Case> responses) {
        respond(responses, () -> {
            String caller = caller();
            if (!directory.isDetector(caller)) {
                throw new SecurityException(caller + " is not a detector");
            }
            return toProto(cases.raise(alert(request.getAlert())));
        });
    }

    @Override
    public void listCases(ListCasesRequest request, StreamObserver<ListCasesResponse> responses) {
        respond(responses, () -> {
            investigator();
            if (request.getStatus() == CaseStatus.CASE_STATUS_UNSPECIFIED) {
                throw new IllegalArgumentException("status is required");
            }
            ListCasesResponse.Builder response = ListCasesResponse.newBuilder();
            cases.withStatus(status(request.getStatus())).forEach(c -> response.addCases(toProto(c)));
            return response.build();
        });
    }

    @Override
    public void getCase(GetCaseRequest request, StreamObserver<Case> responses) {
        respond(responses, () -> {
            investigator();
            return toProto(cases.find(request.getCaseId())
                    .orElseThrow(() -> new NoSuchElementException("no case " + request.getCaseId())));
        });
    }

    @Override
    public void assign(AssignRequest request, StreamObserver<Case> responses) {
        respond(responses, () -> toProto(cases.assign(investigator(), request.getCaseId(),
                directory.investigator(text(request.getAnalyst(), "analyst")))));
    }

    @Override
    public void addNote(AddNoteRequest request, StreamObserver<Case> responses) {
        respond(responses, () -> toProto(cases.note(investigator(), request.getCaseId(), request.getText())));
    }

    @Override
    public void proposeConclusion(ProposeConclusionRequest request, StreamObserver<Case> responses) {
        respond(responses, () -> toProto(cases.propose(investigator(), request.getCaseId(),
                outcome(request.getOutcome()), request.getReason())));
    }

    @Override
    public void approveConclusion(ApproveConclusionRequest request, StreamObserver<Case> responses) {
        respond(responses, () -> toProto(cases.approve(investigator(), request.getCaseId())));
    }

    @Override
    public void returnConclusion(ReturnConclusionRequest request, StreamObserver<Case> responses) {
        respond(responses, () -> toProto(cases.returnToAnalyst(investigator(), request.getCaseId(),
                request.getReason())));
    }

    private static String caller() {
        String caller = CaseAuthentication.CALLER.get();
        if (caller == null) {
            throw new SecurityException("unauthenticated");
        }
        return caller;
    }

    /** The caller as an analyst or supervisor; reads need one of the two roles as well. */
    private Investigator investigator() {
        Investigator investigator = directory.investigator(caller());
        if (investigator.roles().isEmpty()) {
            throw new SecurityException(investigator.name() + " is neither an analyst nor a supervisor");
        }
        return investigator;
    }

    private static <T> void respond(StreamObserver<T> responses, Supplier<T> call) {
        T response;
        try {
            response = call.get();
        } catch (SecurityException e) {
            LOG.log(WARNING, "case call refused: {0}", e.getMessage());
            responses.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (NoSuchElementException e) {
            responses.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (ConcurrentModificationException e) {
            responses.onError(Status.ABORTED.withDescription(e.getMessage() + "; read the case again")
                    .asRuntimeException());
            return;
        } catch (IllegalStateException e) {
            responses.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (IllegalArgumentException | NullPointerException e) {
            responses.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        }
        responses.onNext(response);
        responses.onCompleted();
    }

    private static Alert alert(vn.io.dxa.openframl.cases.v1.Alert a) {
        return new Alert(text(a.getId(), "alert.id"), text(a.getSource(), "alert.source"),
                text(a.getType(), "alert.type"), text(a.getSubject(), "alert.subject"),
                a.getEventId().isBlank() ? Optional.empty() : Optional.of(a.getEventId()),
                a.hasScore() ? Optional.of(score(a.getScore())) : Optional.empty(), a.getEvidenceMap(),
                a.hasRaisedAt() ? instant(a.getRaisedAt()) : Instant.now());
    }

    private static double score(double score) {
        if (score < 0 || score > 1) {
            throw new IllegalArgumentException("alert.score must be between 0 and 1");
        }
        return score;
    }

    static Case toProto(CaseFile c) {
        Case.Builder proto = Case.newBuilder().setId(c.id()).setSubject(c.subject())
                .setStatus(CaseStatus.valueOf("CASE_STATUS_" + c.status().name()))
                .setOpenedAt(timestamp(c.openedAt()));
        c.assignee().ifPresent(proto::setAssignee);
        c.proposal().ifPresent(p -> proto.setProposal(conclusion(p)));
        c.conclusion().ifPresent(p -> proto.setConclusion(conclusion(p)));
        for (Alert a : c.alerts()) {
            vn.io.dxa.openframl.cases.v1.Alert.Builder alert = vn.io.dxa.openframl.cases.v1.Alert.newBuilder()
                    .setId(a.id()).setSource(a.source()).setType(a.type()).setSubject(a.subject())
                    .putAllEvidence(a.evidence()).setRaisedAt(timestamp(a.raisedAt()));
            a.eventId().ifPresent(alert::setEventId);
            a.score().ifPresent(alert::setScore);
            proto.addAlerts(alert);
        }
        for (CaseFile.Action action : c.history()) {
            proto.addHistory(vn.io.dxa.openframl.cases.v1.Action.newBuilder().setAt(timestamp(action.at()))
                    .setActor(action.actor()).setWhat(action.what()).setDetail(action.detail()));
        }
        return proto.build();
    }

    private static vn.io.dxa.openframl.cases.v1.Conclusion conclusion(CaseFile.Conclusion c) {
        vn.io.dxa.openframl.cases.v1.Conclusion.Builder proto = vn.io.dxa.openframl.cases.v1.Conclusion.newBuilder()
                .setOutcome(Outcome.valueOf("OUTCOME_" + c.outcome().name())).setReason(c.reason())
                .setProposedBy(c.proposedBy());
        c.approvedBy().ifPresent(proto::setApprovedBy);
        return proto.build();
    }

    private static CaseFile.Status status(CaseStatus status) {
        return CaseFile.Status.valueOf(status.name().substring("CASE_STATUS_".length()));
    }

    private static CaseFile.Outcome outcome(Outcome outcome) {
        if (outcome == Outcome.OUTCOME_UNSPECIFIED || outcome == Outcome.UNRECOGNIZED) {
            throw new IllegalArgumentException("outcome is required");
        }
        return CaseFile.Outcome.valueOf(outcome.name().substring("OUTCOME_".length()));
    }

    private static String text(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static Timestamp timestamp(Instant at) {
        return Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()).build();
    }

    private static Instant instant(Timestamp t) {
        return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
    }
}
