//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.cases.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.openframl.cases.CaseService;
import vn.io.dxa.openframl.cases.memory.InMemoryCaseStore;
import vn.io.dxa.openframl.cases.v1.AddNoteRequest;
import vn.io.dxa.openframl.cases.v1.Alert;
import vn.io.dxa.openframl.cases.v1.ApproveConclusionRequest;
import vn.io.dxa.openframl.cases.v1.AssignRequest;
import vn.io.dxa.openframl.cases.v1.Case;
import vn.io.dxa.openframl.cases.v1.CaseServiceGrpc;
import vn.io.dxa.openframl.cases.v1.CaseServiceGrpc.CaseServiceBlockingStub;
import vn.io.dxa.openframl.cases.v1.CaseStatus;
import vn.io.dxa.openframl.cases.v1.GetCaseRequest;
import vn.io.dxa.openframl.cases.v1.ListCasesRequest;
import vn.io.dxa.openframl.cases.v1.Outcome;
import vn.io.dxa.openframl.cases.v1.ProposeConclusionRequest;
import vn.io.dxa.openframl.cases.v1.RaiseAlertRequest;

class CaseGrpcServiceTest {

    private static final Metadata.Key<String> AS = Metadata.Key.of("test-caller", Metadata.ASCII_STRING_MARSHALLER);
    private static final Context.Key<String> NAME = Context.key("test-caller");

    private final CaseDirectory directory = new CaseDirectory(Set.of("lan.analyst", "tu.both"),
            Set.of("ha.supervisor", "tu.both"), Set.of("screening-node-1"));
    private final CaseGrpcService service = new CaseGrpcService(
            new CaseService(new InMemoryCaseStore(), Clock.systemUTC()), directory);

    @TempDir
    Path certificates;

    private Server server;
    private ManagedChannel channel;

    /** Tests only: the caller's name from a header, where production takes it from the client certificate. */
    private static final class HeaderCaller implements ServerInterceptor {
        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                ServerCallHandler<Q, R> next) {
            return Contexts.interceptCall(Context.current().withValue(NAME, headers.get(AS)), call, headers, next);
        }
    }

    @BeforeEach
    void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().intercept(new HeaderCaller())
                .addService(ServerInterceptors.intercept(service,
                        new CaseAuthentication(call -> Optional.ofNullable(NAME.get()))))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stop() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private CaseServiceBlockingStub as(String caller) {
        Metadata headers = new Metadata();
        if (caller != null) {
            headers.put(AS, caller);
        }
        return CaseServiceGrpc.newBlockingStub(channel).withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static RaiseAlertRequest alert(String id) {
        return RaiseAlertRequest.newBuilder().setAlert(Alert.newBuilder().setId(id).setSource("screening")
                .setType("BENEFICIARY_NAME_MATCH").setSubject("cust-1").setEventId("evt-" + id).setScore(0.91)
                .putEvidence("list", "UN-SC").putEvidence("entry", "XAi.001")).build();
    }

    private static Status.Code code(Runnable call) {
        return assertThrows(StatusRuntimeException.class, call::run).getStatus().getCode();
    }

    @Test
    void aDetectorRaisesAnAnalystConcludesASupervisorApproves() {
        Case opened = as("screening-node-1").raiseAlert(alert("a1"));
        as("screening-node-1").raiseAlert(alert("a1"));
        assertEquals(CaseStatus.CASE_STATUS_OPEN, opened.getStatus());
        assertEquals(1, as("lan.analyst").listCases(ListCasesRequest.newBuilder()
                .setStatus(CaseStatus.CASE_STATUS_OPEN).build()).getCasesCount());

        CaseServiceBlockingStub lan = as("lan.analyst");
        lan.assign(AssignRequest.newBuilder().setCaseId(opened.getId()).setAnalyst("lan.analyst").build());
        lan.addNote(AddNoteRequest.newBuilder().setCaseId(opened.getId()).setText("Birth year differs.").build());
        lan.proposeConclusion(ProposeConclusionRequest.newBuilder().setCaseId(opened.getId())
                .setOutcome(Outcome.OUTCOME_FALSE_POSITIVE).setReason("A namesake.").build());
        Case closed = as("ha.supervisor").approveConclusion(ApproveConclusionRequest.newBuilder()
                .setCaseId(opened.getId()).build());

        assertEquals(CaseStatus.CASE_STATUS_CLOSED, closed.getStatus());
        assertEquals("ha.supervisor", closed.getConclusion().getApprovedBy());
        assertEquals(1, closed.getAlertsCount());
        assertEquals(0.91, closed.getAlerts(0).getScore());
        assertEquals("UN-SC", closed.getAlerts(0).getEvidenceMap().get("list"));
        assertEquals(List.of("OPENED", "ASSIGNED", "NOTE", "PROPOSED", "APPROVED"),
                closed.getHistoryList().stream().map(a -> a.getWhat()).toList());
        assertEquals("lan.analyst", closed.getHistory(1).getActor());
    }

    @Test
    void eachRefusalHasItsStatus() {
        Case c = as("screening-node-1").raiseAlert(alert("a1"));
        String id = c.getId();

        assertEquals(Status.Code.UNAUTHENTICATED, code(() -> as(null).getCase(
                GetCaseRequest.newBuilder().setCaseId(id).build())));
        assertEquals(Status.Code.PERMISSION_DENIED, code(() -> as("eve").getCase(
                GetCaseRequest.newBuilder().setCaseId(id).build())));
        assertEquals(Status.Code.PERMISSION_DENIED, code(() -> as("lan.analyst").raiseAlert(alert("a2"))),
                "analysts do not raise alerts");
        assertEquals(Status.Code.PERMISSION_DENIED, code(() -> as("screening-node-1").getCase(
                GetCaseRequest.newBuilder().setCaseId(id).build())), "detectors do not read cases");
        assertEquals(Status.Code.NOT_FOUND, code(() -> as("lan.analyst").getCase(
                GetCaseRequest.newBuilder().setCaseId("nope").build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> as("screening-node-1").raiseAlert(
                RaiseAlertRequest.newBuilder().setAlert(Alert.newBuilder().setId("x")).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> as("lan.analyst").listCases(
                ListCasesRequest.getDefaultInstance())));

        as("tu.both").assign(AssignRequest.newBuilder().setCaseId(id).setAnalyst("tu.both").build());
        as("tu.both").proposeConclusion(ProposeConclusionRequest.newBuilder().setCaseId(id)
                .setOutcome(Outcome.OUTCOME_CONFIRMED).setReason("Same passport.").build());
        assertEquals(Status.Code.PERMISSION_DENIED, code(() -> as("tu.both").approveConclusion(
                ApproveConclusionRequest.newBuilder().setCaseId(id).build())), "four eyes");
        assertEquals(Status.Code.FAILED_PRECONDITION, code(() -> as("ha.supervisor").assign(
                AssignRequest.newBuilder().setCaseId(id).setAnalyst("lan.analyst").build())),
                "a case awaiting approval is not reassigned");
    }

    @Test
    void overMutualTlsTheCertificateIsTheCaller() throws Exception {
        TestCertificates bank = TestCertificates.authority(certificates, "bank-cases");
        Path serverCert = bank.issue("case-service", "localhost");
        Path lan = bank.issue("lan.analyst", null);
        try (CaseServer tls = CaseServer.start(0, CaseServer.mutualTls(serverCert.toFile(),
                Path.of(serverCert + ".key").toFile(), bank.caFile().toFile()), service, new CaseAuthentication())) {
            ManagedChannel secure = Grpc.newChannelBuilderForAddress("localhost", tls.port(),
                    TlsChannelCredentials.newBuilder().trustManager(bank.caFile().toFile())
                            .keyManager(lan.toFile(), Path.of(lan + ".key").toFile()).build()).build();
            try {
                assertEquals(0, CaseServiceGrpc.newBlockingStub(secure).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .listCases(ListCasesRequest.newBuilder().setStatus(CaseStatus.CASE_STATUS_CLOSED).build())
                        .getCasesCount());
            } finally {
                secure.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
            ManagedChannel anonymous = Grpc.newChannelBuilderForAddress("localhost", tls.port(),
                    TlsChannelCredentials.newBuilder().trustManager(bank.caFile().toFile()).build()).build();
            try {
                assertEquals(Status.Code.UNAVAILABLE, code(() -> CaseServiceGrpc.newBlockingStub(anonymous)
                        .withDeadlineAfter(10, TimeUnit.SECONDS).listCases(ListCasesRequest.newBuilder()
                                .setStatus(CaseStatus.CASE_STATUS_OPEN).build())));
            } finally {
                anonymous.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }
}
