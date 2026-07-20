package com.platform.wikibackend.permission;

import com.platform.proto.org.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** GrpcPermissionClient 단위 검증 — in-process 가짜 org 서버로 매핑·캐시·fail-closed를 본다. */
class GrpcPermissionClientTest {

    static class StubOrg extends PermissionServiceGrpc.PermissionServiceImplBase {
        final AtomicInteger checkCalls = new AtomicInteger();
        volatile boolean allow = true;
        volatile boolean fail = false;

        @Override public void checkPermission(CheckPermissionRequest req, StreamObserver<CheckPermissionResponse> out) {
            checkCalls.incrementAndGet();
            if (fail) { out.onError(io.grpc.Status.UNAVAILABLE.asRuntimeException()); return; }
            out.onNext(CheckPermissionResponse.newBuilder().setAllowed(allow).build());
            out.onCompleted();
        }

        @Override public void listUserGrants(ListUserGrantsRequest req, StreamObserver<ListUserGrantsResponse> out) {
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
    void org_불능이면_fail_closed_false() {
        stubOrg.fail = true;
        assertThat(client.isAllowed(2L, 5L, WikiAction.VIEW)).isFalse();
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
