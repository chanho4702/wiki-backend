package com.platform.wikibackend.config;

import com.platform.common.grpc.InternalTokenClientInterceptor;
import com.platform.common.grpc.InternalTokenServerInterceptor;
import com.platform.proto.org.v1.ListUserTeamsRequest;
import com.platform.proto.org.v1.ListUserTeamsResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * org 채널은 모든 호출에 서비스 간 토큰을 싣는다(P-01, common-starter 0.17.0).
 * 권한·팀·주체·멤버 디렉터리가 같은 채널을 쓰므로 채널 빌더 하나를 검증하면 넷 다 덮인다.
 */
class OrgChannelTokenTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void teardown() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private String callAndCapture(InternalTokenClientInterceptor tokens) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                         ServerCallHandler<ReqT, RespT> next) {
                seen.set(headers.get(InternalTokenServerInterceptor.HEADER));
                return next.startCall(call, headers);
            }
        };
        PermissionServiceGrpc.PermissionServiceImplBase org = new PermissionServiceGrpc.PermissionServiceImplBase() {
            @Override
            public void listUserTeams(ListUserTeamsRequest req, StreamObserver<ListUserTeamsResponse> out) {
                out.onNext(ListUserTeamsResponse.getDefaultInstance());
                out.onCompleted();
            }
        };
        server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(ServerInterceptors.intercept(org, capture)).build().start();
        channel = SecurityConfig.buildOrgChannel("127.0.0.1", server.getPort(), tokens);
        PermissionServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                .listUserTeams(ListUserTeamsRequest.newBuilder().setUserId(1).build());
        return seen.get();
    }

    @Test
    void 토큰이_있으면_메타데이터에_싣는다() throws Exception {
        assertThat(callAndCapture(new InternalTokenClientInterceptor("platform-secret"))).isEqualTo("platform-secret");
    }

    @Test
    void 토큰이_비면_싣지_않는다() throws Exception {
        assertThat(callAndCapture(new InternalTokenClientInterceptor(""))).isNull();
    }
}
