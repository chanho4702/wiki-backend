package com.platform.wikibackend.grpc;

import com.platform.common.grpc.GrpcAuthMode;
import com.platform.common.grpc.InternalTokenClientInterceptor;
import com.platform.common.grpc.InternalTokenServerInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 위키 콘텐츠 gRPC 서버의 서비스 간 토큰 검사와 리플렉션 끄기(P-01, common-starter 0.17.0).
 *
 * 실제 콘텐츠 서비스 대신 health 서비스를 같은 배선(ServerInterceptors.intercept)으로 띄워 문만 본다 —
 * 콘텐츠 서비스의 응답은 WikiContentGrpcServiceTest가 맡는다.
 */
class GrpcServerTokenTest {

    private GrpcServerLifecycle lifecycle;
    private ManagedChannel channel;

    @AfterEach
    void teardown() {
        if (channel != null) channel.shutdownNow();
        if (lifecycle != null) lifecycle.stop();
    }

    private void start(GrpcAuthMode mode, boolean reflection) {
        HealthStatusManager health = new HealthStatusManager();
        lifecycle = new GrpcServerLifecycle(health.getHealthService(), new InternalTokenServerInterceptor(mode, "secret"));
        ReflectionTestUtils.setField(lifecycle, "port", 0);
        ReflectionTestUtils.setField(lifecycle, "bindAddress", "127.0.0.1");
        ReflectionTestUtils.setField(lifecycle, "reflectionEnabled", reflection);
        lifecycle.start();
    }

    private HealthCheckResponse check(String token) {
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", lifecycle.boundPort()).usePlaintext()
                .intercept(new InternalTokenClientInterceptor(token)).build();
        return HealthGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                .check(HealthCheckRequest.getDefaultInstance());
    }

    @Test
    void enforce면_토큰_없는_호출을_UNAUTHENTICATED로_끊는다() {
        start(GrpcAuthMode.ENFORCE, false);

        assertThatThrownBy(() -> check(""))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void enforce에서도_맞는_토큰은_통과한다() {
        start(GrpcAuthMode.ENFORCE, false);

        assertThat(check("secret").getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
    }

    @Test
    void monitor_기본_모드는_토큰이_없어도_통과시킨다() {
        start(GrpcAuthMode.MONITOR, false);

        assertThat(check("").getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
    }

    @Test
    void 리플렉션은_기본으로_꺼져_있다() throws Exception {
        start(GrpcAuthMode.MONITOR, false);
        check("secret"); // 채널 준비

        assertThat(reflectionStatus()).isEqualTo(Status.Code.UNIMPLEMENTED);
    }

    @Test
    void 리플렉션을_켜면_응답한다() throws Exception {
        start(GrpcAuthMode.MONITOR, true);
        check("secret");

        assertThat(reflectionStatus()).isEqualTo(Status.Code.OK);
    }

    private Status.Code reflectionStatus() throws Exception {
        CompletableFuture<Status.Code> done = new CompletableFuture<>();
        StreamObserver<ServerReflectionRequest> requests = ServerReflectionGrpc.newStub(channel)
                .serverReflectionInfo(new StreamObserver<>() {
                    @Override public void onNext(ServerReflectionResponse value) { done.complete(Status.Code.OK); }
                    @Override public void onError(Throwable t) { done.complete(Status.fromThrowable(t).getCode()); }
                    @Override public void onCompleted() { done.complete(Status.Code.OK); }
                });
        requests.onNext(ServerReflectionRequest.newBuilder().setListServices("").build());
        Status.Code code = done.get(5, TimeUnit.SECONDS);
        requests.onCompleted();
        return code;
    }
}
