//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.gate.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.io.dxa.openframl.gate.GatePolicy;
import vn.io.dxa.openframl.gate.PolicyAdmin;
import vn.io.dxa.openframl.gate.admin.v1.GetActiveRequest;
import vn.io.dxa.openframl.gate.admin.v1.ListPendingRequest;
import vn.io.dxa.openframl.gate.admin.v1.PolicyAdminGrpc;
import vn.io.dxa.openframl.gate.admin.v1.ProposeRequest;
import vn.io.dxa.openframl.gate.memory.InMemoryPolicyRepository;
import vn.io.dxa.openframl.pack.CountryPack;
import vn.io.dxa.rulestone.core.DecisionTableJson;

/** The admin service over real mutual TLS: the caller is the certificate's common name, and nothing else gets in. */
class AdminMutualTlsTest {

    @TempDir
    Path directory;

    private final CountryPack pack = CountryPack.forCountry("VN");
    private final GatePolicy initial = GatePolicy.of(pack);
    private final List<ManagedChannel> channels = new ArrayList<>();
    private TestCertificates bank;
    private GateServer admin;

    @BeforeEach
    void start() throws Exception {
        bank = TestCertificates.authority(directory, "bank-admin");
        Path server = bank.issue("gate-admin", "localhost");
        admin = GateServer.startAdmin(0,
                GateServer.mutualTls(server.toFile(), Path.of(server + ".key").toFile(), bank.caFile().toFile()),
                new PolicyAdminGrpcService(new PolicyAdmin(pack, new InMemoryPolicyRepository(), initial,
                        Clock.systemUTC())),
                new AdminAuthentication(Set.of("an.maker"), Set.of("binh.checker")));
    }

    @AfterEach
    void stop() throws InterruptedException {
        for (ManagedChannel channel : channels) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        admin.close();
    }

    private PolicyAdminGrpc.PolicyAdminBlockingStub client(Path certificate) throws Exception {
        TlsChannelCredentials.Builder tls = TlsChannelCredentials.newBuilder().trustManager(bank.caFile().toFile());
        if (certificate != null) {
            tls.keyManager(certificate.toFile(), Path.of(certificate + ".key").toFile());
        }
        ChannelCredentials credentials = tls.build();
        ManagedChannel channel = Grpc.newChannelBuilderForAddress("localhost", admin.port(), credentials).build();
        channels.add(channel);
        return PolicyAdminGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    @Test
    void theCertificatesCommonNameIsTheCaller() throws Exception {
        String toId = client(bank.issue("an.maker", null)).propose(ProposeRequest.newBuilder()
                .addAllTables(initial.tables().stream().map(DecisionTableJson::write)
                        .map(json -> json.replace("money(20000000, 'VND')", "money(15000000, 'VND')")).toList())
                .setReason("CHG-1").build()).getToPolicyId();

        var pending = client(bank.issue("binh.checker", null)).listPending(ListPendingRequest.getDefaultInstance());
        assertEquals("an.maker", pending.getProposals(0).getMaker());
        assertEquals(toId, pending.getProposals(0).getToPolicyId());
    }

    @Test
    void withoutABankCertificateNothingGetsIn() throws Exception {
        TestCertificates stranger = TestCertificates.authority(directory, "elsewhere");
        Path foreign = stranger.issue("binh.checker", null);

        for (Path certificate : new Path[] {null, foreign}) {
            StatusRuntimeException refused = assertThrows(StatusRuntimeException.class,
                    () -> client(certificate).getActive(GetActiveRequest.getDefaultInstance()));
            assertEquals(Status.Code.UNAVAILABLE, refused.getStatus().getCode(), refused::toString);
        }
        assertEquals(Status.Code.PERMISSION_DENIED, assertThrows(StatusRuntimeException.class,
                () -> client(bank.issue("eve", null)).getActive(GetActiveRequest.getDefaultInstance()))
                .getStatus().getCode());
    }
}
