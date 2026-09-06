package com.platform.wikibackend.config;

import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.wikibackend.directory.GrpcMemberDirectory;
import com.platform.wikibackend.directory.MemberDirectory;
import com.platform.wikibackend.permission.GrpcPermissionClient;
import com.platform.wikibackend.permission.GrpcTeamDirectory;
import com.platform.wikibackend.permission.GrpcPrincipalDirectory;
import com.platform.wikibackend.permission.PrincipalDirectory;
import com.platform.wikibackend.permission.TeamDirectory;
import com.platform.wikibackend.permission.PermissionClient;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT 디코더(JWKS + issuer/audience 검증)와 roles→ROLE_ 변환기는 common-starter가 준다(S-02).
 * 여기에는 이 서비스만의 것 — 어떤 경로를 열지, org gRPC 채널과 클라이언트 — 만 남긴다.
 *
 * `docs` 프로필(공개 문서 인스턴스)에서는 이 구성 전체가 빠지고 {@link DocsSecurityConfig}가
 * 대신 선다. 필터 체인만 빼지 않는 이유: 거기서는 JWT 디코더도 org gRPC 채널도 존재하지 않아야
 * 하는데, @ConditionalOnMissingBean은 빈 등록 순서에 기대는 약한 대체라 프로필로 확실히 끊는다.
 */
@Configuration
@Profile("!docs")
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                // 위키는 공개 엔드포인트 없음 — 조회 권한도 스페이스 단위 VIEW로 서비스 계층에서 판정.
                // 예외는 OpenAPI 스펙 하나뿐이다: 게이트웨이·nginx가 /v3를 라우팅하지 않아
                // 클러스터 밖에서는 닿지 않고, 수집기(myFront scripts/api)는 토큰 없이 컨테이너
                // 네트워크에서 긁어 간다. 공개 문서 인스턴스(docs 프로필)는 이 체인을 타지 않는다.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                        // 헬스·빌드정보 — 게이트웨이의 플랫폼 상태 집계가 컨테이너 네트워크에서
                        // 토큰 없이 프로브한다. 노출 목록(management.endpoints.web.exposure.include)에
                        // 이 둘만 있으므로 나머지 /actuator/** 는 애초에 매핑되지 않는다.
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    /** org gRPC 채널 — 권한 판정·팀 멤버십(W18)·사용자 디렉터리가 공유한다. */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "orgChannel")
    io.grpc.ManagedChannel orgChannel(
            @Value("${platform.org-grpc.host}") String host,
            @Value("${platform.org-grpc.port}") int port) {
        // 콜드 스타트 방지: 첫 요청이 이름 해석·연결 수립에 1초 넘게 걸려 DEADLINE_EXCEEDED(→503)가 나던 것을
        // 시작 시 연결을 선점(getState(true))하고 keepalive로 유휴 끊김을 막아 없앤다(alm-backend 2026-09-05 실측).
        io.grpc.ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(30, java.util.concurrent.TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
        channel.getState(true);
        return channel;
    }

    /** 테스트는 FakePermissionClient 빈이 이 빈을 대체한다(@ConditionalOnMissingBean). */
    @Bean
    @ConditionalOnMissingBean(PermissionClient.class)
    PermissionClient permissionClient(io.grpc.ManagedChannel orgChannel,
                                      @Value("${platform.org-grpc.deadline-seconds:5}") long deadlineSeconds) {
        // waitForReady: 연결이 아직 없으면 데드라인 안에서 기다린다(콜드 스타트에 즉시 UNAVAILABLE로 떨어지지 않게)
        return new GrpcPermissionClient(
                PermissionServiceGrpc.newBlockingStub(orgChannel).withWaitForReady(), deadlineSeconds);
    }

    /** 계정 상태 게이트가 읽는 사람의 원장 — 권한과 같은 채널을 쓴다. */
    @Bean
    @ConditionalOnMissingBean(MemberDirectory.class)
    MemberDirectory memberDirectory(io.grpc.ManagedChannel orgChannel,
                                    @Value("${platform.org-grpc.deadline-seconds:5}") long deadlineSeconds) {
        return new GrpcMemberDirectory(
                PermissionServiceGrpc.newBlockingStub(orgChannel).withWaitForReady(), deadlineSeconds);
    }

    /** W18 TEAM 주체 판정 — 테스트는 FakeTeamDirectory(@Primary)가 대체한다. */
    @Bean
    @ConditionalOnMissingBean(TeamDirectory.class)
    TeamDirectory teamDirectory(io.grpc.ManagedChannel orgChannel) {
        return new GrpcTeamDirectory(PermissionServiceGrpc.newBlockingStub(orgChannel));
    }

    /** W18 제한 저장 전 USER/TEAM 실재 검증 — org 원장 불능 시 저장을 fail-closed한다. */
    @Bean
    @ConditionalOnMissingBean(PrincipalDirectory.class)
    PrincipalDirectory principalDirectory(io.grpc.ManagedChannel orgChannel) {
        return new GrpcPrincipalDirectory(PermissionServiceGrpc.newBlockingStub(orgChannel));
    }
}
