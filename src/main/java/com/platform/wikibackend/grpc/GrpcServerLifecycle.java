package com.platform.wikibackend.grpc;

import com.platform.common.grpc.InternalTokenServerInterceptor;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 순정 grpc-java 서버 수명주기 — org-service의 같은 이름 클래스와 동일한 패턴(스타터 없이 SmartLifecycle).
 *
 * 켜는 조건은 {@link WikiGrpcServerCondition}(SEARCH_MODE=lite면 끔).
 * 서비스 간 토큰(P-01, common-starter 0.17.0): 콘텐츠 서비스에 스타터 서버 인터셉터를 건다 — 모드는
 * org-service와 같은 키 {@code PLATFORM_GRPC_AUTH_MODE}(off | monitor 기본 | enforce), 토큰은 {@code PLATFORM_GRPC_TOKEN}.
 * 리플렉션(grpcurl용)은 운영에서 끈다 — 무인증 시절에 계약 전체를 내보이던 창구다. 디버깅할 때만
 * {@code WIKI_GRPC_REFLECTION_ENABLED=true}로 켠다(리플렉션 자체도 같은 토큰 검사를 받는다).
 */
@Component
@Conditional(WikiGrpcServerCondition.class)
@Slf4j
public class GrpcServerLifecycle implements SmartLifecycle {

    private final BindableService wikiContentGrpcService;
    private final InternalTokenServerInterceptor tokenInterceptor;

    @org.springframework.beans.factory.annotation.Autowired
    public GrpcServerLifecycle(WikiContentGrpcService wikiContentGrpcService,
                               InternalTokenServerInterceptor platformGrpcServerInterceptor) {
        this((BindableService) wikiContentGrpcService, platformGrpcServerInterceptor);
    }

    /** 테스트용 — 실제 콘텐츠 서비스 대신 아무 서비스나 같은 배선으로 띄운다. */
    GrpcServerLifecycle(BindableService service, InternalTokenServerInterceptor tokenInterceptor) {
        this.wikiContentGrpcService = service;
        this.tokenInterceptor = tokenInterceptor;
    }

    @Value("${platform.grpc.reflection-enabled:false}")
    private boolean reflectionEnabled;

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
            NettyServerBuilder builder = NettyServerBuilder.forAddress(new java.net.InetSocketAddress(bindAddress, port))
                    .addService(ServerInterceptors.intercept(wikiContentGrpcService, tokenInterceptor));
            if (reflectionEnabled) {
                builder.addService(ServerInterceptors.intercept(ProtoReflectionService.newInstance(), tokenInterceptor));
            }
            server = builder.build().start();
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

    /** 실제로 바인드된 포트 — 설정 포트 0(임의 포트, 테스트)일 때 읽는다. */
    int boundPort() {
        return server == null ? -1 : server.getPort();
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}
