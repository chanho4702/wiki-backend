package com.platform.wikibackend.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 순정 grpc-java 서버 수명주기 — org-service의 같은 이름 클래스와 동일한 패턴(스타터 없이 SmartLifecycle).
 * 리플렉션 서비스 포함 — grpcurl로 계약 탐색/디버깅 가능.
 */
@Component
@ConditionalOnProperty(value = "platform.grpc.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class GrpcServerLifecycle implements SmartLifecycle {

    private final WikiContentGrpcService wikiContentGrpcService;

    @Value("${platform.grpc.port:9111}")
    private int port;

    private Server server;

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(port)
                    .addService(wikiContentGrpcService)
                    .addService(ProtoReflectionService.newInstance())
                    .build()
                    .start();
            log.info("gRPC 서버 기동: :{}", port);
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
