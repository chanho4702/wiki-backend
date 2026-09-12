package com.platform.wikibackend.permission;

import com.platform.proto.org.v1.*;
import com.platform.common.error.ServiceUnavailableException;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GrpcPermissionClient 단위 검증 — in-process 가짜 org 서버로 매핑·캐시·fail-closed를 본다. */
class GrpcPermissionClientTest {

    static class StubOrg extends PermissionServiceGrpc.PermissionServiceImplBase {
        final AtomicInteger checkCalls = new AtomicInteger();
        volatile boolean allow = true;
        volatile boolean fail = false;
        volatile Status failStatus = Status.UNAVAILABLE;
        volatile String deniedReason = "";
        volatile CheckPermissionRequest lastCheck;

        @Override public void checkPermission(CheckPermissionRequest req, StreamObserver<CheckPermissionResponse> out) {
            checkCalls.incrementAndGet();
            lastCheck = req;
            if (fail) { out.onError(failStatus.asRuntimeException()); return; }
            out.onNext(CheckPermissionResponse.newBuilder()
                    .setAllowed(allow)
                    .setDeniedReason(deniedReason)
                    .build());
            out.onCompleted();
        }

        @Override public void listUserGrants(ListUserGrantsRequest req, StreamObserver<ListUserGrantsResponse> out) {
            if (fail) { out.onError(failStatus.asRuntimeException()); return; }
            out.onNext(ListUserGrantsResponse.newBuilder()
                    .addGrants(Grant.newBuilder().setResourceType(ResourceType.SPACE).setResourceId("3").setRole(Role.VIEWER))
                    .addGrants(Grant.newBuilder().setResourceType(ResourceType.GLOBAL).setResourceId("").setRole(Role.VIEWER))
                    .build());
            out.onCompleted();
        }

        @Override public void createGrant(CreateGrantRequest req, StreamObserver<CreateGrantResponse> out) {
            out.onNext(CreateGrantResponse.newBuilder().setCreated(true).build());
            out.onCompleted();
        }
    }

    StubOrg stubOrg = new StubOrg();
    Server server;
    ManagedChannel channel;
    GrpcPermissionClient client;

    @BeforeEach
    void setup() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(stubOrg).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = new GrpcPermissionClient(PermissionServiceGrpc.newBlockingStub(channel));
    }

    @AfterEach
    void teardown() throws InterruptedException {
        channel.shutdownNow(); server.shutdownNow();
        channel.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
        server.awaitTermination();
    }

    @Test
    void 허용_판정이_전달되고_30초_캐시로_중복호출이_제거된다() {
        assertThat(client.isAllowed(1L, 5L, WikiAction.EDIT)).isTrue();
        assertThat(client.isAllowed(1L, 5L, WikiAction.EDIT)).isTrue(); // 캐시 히트
        assertThat(stubOrg.checkCalls.get()).isEqualTo(1);
    }

    @Test
    void org_불능_UNAVAILABLE이면_503으로_전파한다() {
        // org-service 다운(전송 장애) → fail-closed로 조용히 삼키지 않고 ServiceUnavailableException(→503) 전파
        stubOrg.fail = true;
        stubOrg.failStatus = Status.UNAVAILABLE;
        assertThatThrownBy(() -> client.isAllowed(2L, 5L, WikiAction.VIEW))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> client.accessibleSpaces(2L))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void 가용성_외_gRPC_오류는_여전히_fail_closed다() {
        // 전송 장애가 아닌 오류(예: INTERNAL)는 기존대로 안전하게 fail-closed(거부/빈 목록)
        stubOrg.fail = true;
        stubOrg.failStatus = Status.INTERNAL;
        assertThat(client.isAllowed(3L, 5L, WikiAction.VIEW)).isFalse();
        assertThat(client.accessibleSpaces(3L).all()).isFalse();
    }

    /** 0.16.0 denied_reason이 판정에 실려 온다 — 캐시도 사유째로 저장한다 */
    @Test
    void 거부_사유가_판정에_실려_오고_캐시된다() {
        stubOrg.allow = false;
        stubOrg.deniedReason = "SUSPENDED";

        PermissionDecision first = client.check(7L, 5L, WikiAction.VIEW);
        assertThat(first.allowed()).isFalse();
        assertThat(first.deniedReason()).isEqualTo("SUSPENDED");
        assertThat(first.accountMessage()).isEqualTo("정지된 계정입니다");

        assertThat(client.check(7L, 5L, WikiAction.VIEW).deniedReason()).isEqualTo("SUSPENDED");
        assertThat(stubOrg.checkCalls.get()).isEqualTo(1); // 사유째로 캐시 히트
    }

    /** 권한만 모자란 거부는 계정 문구를 만들지 않는다 — 호출부가 자기 맥락의 문구를 쓴다 */
    @Test
    void 권한_부족_사유는_계정_문구가_없다() {
        stubOrg.allow = false;
        stubOrg.deniedReason = "NO_GRANT";

        assertThat(client.check(8L, 5L, WikiAction.EDIT).accountMessage()).isNull();
    }

    /** 모르는 사유는 일반 거부다 — 값은 뒤에 늘 수 있다 */
    @Test
    void 모르는_사유는_일반_거부로_다룬다() {
        stubOrg.allow = false;
        stubOrg.deniedReason = "SOME_FUTURE_REASON";

        PermissionDecision decision = client.check(9L, 5L, WikiAction.EDIT);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.accountMessage()).isNull();
    }

    /** 가용성 외 실패의 fail-closed는 <b>사유 없는</b> 거부다 — 장애를 "정지된 계정"으로 말하지 않는다 */
    @Test
    void fail_closed_거부에는_사유가_없다() {
        stubOrg.fail = true;
        stubOrg.failStatus = Status.INTERNAL;

        PermissionDecision decision = client.check(10L, 5L, WikiAction.VIEW);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.deniedReason()).isEmpty();
        assertThat(decision.accountMessage()).isNull();
    }

    @Test
    void 계정_상태_문구는_네_갈래다() {
        assertThat(PermissionDecision.accountMessage("PENDING")).isEqualTo("승인 대기 중인 계정입니다");
        assertThat(PermissionDecision.accountMessage("SUSPENDED")).isEqualTo("정지된 계정입니다");
        assertThat(PermissionDecision.accountMessage("DEACTIVATED")).isEqualTo("비활성된 계정입니다");
        assertThat(PermissionDecision.accountMessage("ACTIVE")).isNull();
        assertThat(PermissionDecision.accountMessage("")).isNull();
        assertThat(PermissionDecision.accountMessage(null)).isNull();
    }

    /** 전역 관리자 판정은 CheckPermission(GLOBAL, ADMIN)이다 — resource_id는 빈 값(proto 계약) */
    @Test
    void checkGlobal은_GLOBAL_리소스로_묻는다() {
        assertThat(client.checkGlobal(1L, WikiAction.ADMIN).allowed()).isTrue();

        assertThat(stubOrg.lastCheck.getResourceType()).isEqualTo(ResourceType.GLOBAL);
        assertThat(stubOrg.lastCheck.getResourceId()).isEmpty();
        assertThat(stubOrg.lastCheck.getAction()).isEqualTo(Action.ADMIN);
        assertThat(stubOrg.lastCheck.getUserId()).isEqualTo(1L);
    }

    /**
     * 캐시 키에 리소스 종류가 들어간다 — 안 들어가면 스페이스 하나의 ADMIN 판정이 전역 관리자
     * 판정으로 재사용되어 권한 상승이 된다.
     */
    @Test
    void 전역_판정과_스페이스_판정은_캐시를_공유하지_않는다() {
        client.check(1L, 5L, WikiAction.ADMIN);
        client.checkGlobal(1L, WikiAction.ADMIN);

        assertThat(stubOrg.checkCalls.get()).isEqualTo(2);
        assertThat(stubOrg.lastCheck.getResourceType()).isEqualTo(ResourceType.GLOBAL);

        client.checkGlobal(1L, WikiAction.ADMIN); // 전역 판정도 제 키로 캐시된다
        assertThat(stubOrg.checkCalls.get()).isEqualTo(2);
    }

    /** 전역 판정도 거부 사유를 싣는다 — 옛 accessibleSpaces().all() 판정에는 없던 정보다 */
    @Test
    void checkGlobal도_거부_사유를_싣는다() {
        stubOrg.allow = false;
        stubOrg.deniedReason = "PENDING";

        PermissionDecision decision = client.checkGlobal(5L, WikiAction.ADMIN);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.accountMessage()).isEqualTo("승인 대기 중인 계정입니다");
    }

    /** org 불능이면 전역 판정도 503이다 — fail-closed로 "관리자가 아니다"라고 말하지 않는다 */
    @Test
    void checkGlobal은_org_불능에_503을_전파한다() {
        stubOrg.fail = true;
        stubOrg.failStatus = Status.DEADLINE_EXCEEDED;

        assertThatThrownBy(() -> client.checkGlobal(6L, WikiAction.ADMIN))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void accessibleSpaces는_GLOBAL_grant를_all로_해석한다() {
        AccessScope scope = client.accessibleSpaces(1L);
        assertThat(scope.all()).isTrue();
    }

    @Test
    void grantSpaceAdmin은_CreateGrant를_호출한다() {
        assertThat(client.grantSpaceAdmin(1L, 9L)).isTrue();
    }
}
