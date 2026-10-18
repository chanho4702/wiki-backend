package com.platform.wikibackend.grpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 순정 grpc-java 서버 수명주기 — org-service의 같은 이름 클래스와 동일한 패턴(스타터 없이 SmartLifecycle).
 * 리플렉션 서비스 포함 — grpcurl로 계약 탐색/디버깅 가능.
 *
 * 켜는 조건은 {@link WikiGrpcServerCondition}(SEARCH_MODE=lite면 끔). 인증(서비스 간 토큰)은 아직 없다 —
 * 보호 수단은 네트워크 격리와 바인드 주소다. 서비스 간 토큰은 플랫폼 공통으로 따로 들인다.
 */
@Component
@Conditional(WikiGrpcServerCondition.class)
@RequiredArgsConstructor
@Slf4j
public class GrpcServerLifecycle implements SmartLifecycle {

    private final WikiContentGrpcService wikiContentGrpcService;

    @Value("${platform.grpc.port:9111}")
    private int port;

    /**
     * 바인드 주소. 기본 0.0.0.0은 컨테이너 안에서 컨테이너 내부망으로만 닿는다(compose가 호스트 포트를
     * 열지 않는다). 호스트에서 bootRun 하는 dev 오프셋 클러스터처럼 그 격리가 없는 곳은 127.0.0.1로 좁힌다.
     */
    @Value("${platform.grpc.bind-address:0.0.0.0}")
    private String bindAddress;

    private Server server;

    @Override
    public void start() {
        try {
            server = NettyServerBuilder.forAddress(new java.net.InetSocketAddress(bindAddress, port))
                    .addService(wikiContentGrpcService)
                    .addService(ProtoReflectionService.newInstance())
                    .build()
                    .start();
            log.info("gRPC 서버 기동: {}:{}", bindAddress, port);
        } catch (IOException e) {
            throw new IllegalStateException("gRPC 서버 기동 실패 :" + port, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
            log.info("gRPC 서버 종료: :{}", port);
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}
