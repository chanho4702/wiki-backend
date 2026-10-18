package com.platform.wikibackend.permission;

import com.platform.common.error.ServiceUnavailableException;
import com.platform.proto.org.v1.ListUserTeamsRequest;
import com.platform.proto.org.v1.ListUserTeamsResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.ValidatePrincipalsRequest;
import com.platform.proto.org.v1.ValidatePrincipalsResponse;
import com.platform.wikibackend.permission.dto.RestrictionPrincipal;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 팀·주체 디렉터리 호출에도 데드라인이 있다(감사 W-10).
 *
 * org-service가 연결은 유지한 채 멈추면 UNAVAILABLE이 아니라 무기한 대기가 되어 톰캣 스레드가 고갈된다.
 * TEAM 제한 문서를 열 때마다 teamsOf를 지나므로 위키 전체가 멈춘다. 데드라인이 지나면 503(장애 전파)이어야 한다.
 */
class GrpcDirectoryDeadlineTest {

    /** 요청을 받고 영영 답하지 않는 org — 연결은 살아 있는 멈춤. */
    static class HangingOrg extends PermissionServiceGrpc.PermissionServiceImplBase {
        @Override public void listUserTeams(ListUserTeamsRequest req, StreamObserver<ListUserTeamsResponse> out) {
            // 답하지 않는다
        }

        @Override public void validatePrincipals(ValidatePrincipalsRequest req,
                                                 StreamObserver<ValidatePrincipalsResponse> out) {
            // 답하지 않는다
        }
    }

    Server server;
    ManagedChannel channel;

    @BeforeEach
    void setup() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).addService(new HangingOrg()).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
    }

    @AfterEach
    void teardown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void 팀_조회가_멈추면_데드라인_뒤_503이다() {
        GrpcTeamDirectory teams = new GrpcTeamDirectory(PermissionServiceGrpc.newBlockingStub(channel), 1);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThatThrownBy(() -> teams.teamsOf(7L)).isInstanceOf(ServiceUnavailableException.class));
    }

    @Test
    void 주체_검증이_멈추면_데드라인_뒤_503이다() {
        GrpcPrincipalDirectory principals = new GrpcPrincipalDirectory(PermissionServiceGrpc.newBlockingStub(channel), 1);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThatThrownBy(() -> principals.requireExisting(List.of(new RestrictionPrincipal("USER", 3L))))
                        .isInstanceOf(ServiceUnavailableException.class));
    }
}
