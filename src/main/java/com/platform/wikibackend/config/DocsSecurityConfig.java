package com.platform.wikibackend.config;

import com.platform.wikibackend.event.EventPublisher;
import com.platform.wikibackend.migration.confluence.restriction.MigrationPrincipalResolver;
import com.platform.wikibackend.migration.confluence.restriction.UnmappedMigrationPrincipalResolver;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.permission.PrincipalDirectory;
import com.platform.wikibackend.permission.TeamDirectory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 공개 문서 인스턴스(docs) 전용 보안 구성. 팀 위키용 {@link SecurityConfig}는 이 프로필에서 통째로 빠진다.
 *
 * 두 가지가 팀 위키와 다르다.
 * 1) 인증이 없다 — JWT 디코더 대신 {@link DocsPrincipalFilter}가 합성 주체를 심는다.
 * 2) 인가를 경로로 판정한다 — 읽기만 열고 나머지는 전부 denyAll. 서비스 계층의 스페이스 권한
 *    판정은 {@link PublicReadPermissionClient}가 대신하지만, 그것을 유일한 방어선으로 두지 않는다.
 *    새 쓰기 엔드포인트가 추가돼도 경로 규칙에 명시하지 않는 한 열리지 않는다(기본 거부).
 */
@Configuration
@Profile("docs")
public class DocsSecurityConfig {

    @Bean
    SecurityFilterChain docsFilterChain(HttpSecurity http,
                                        @Value("${platform.docs.import-token:}") String importToken) throws Exception {
        DocsDenialHandler denied = new DocsDenialHandler();
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                // 인가 판정 직전에 주체를 심는다 — SecurityContextHolderFilter 뒤라 정리도 그쪽이 맡는다.
                // 빈으로 노출하지 않는 이유: Boot가 서블릿 필터로 한 번 더 등록해 버린다.
                .addFilterBefore(new DocsPrincipalFilter(importToken), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 조회수는 공개 인스턴스에서 세지 않는다 — 임포터에게도 열지 않는다.
                        // GET 허용 규칙보다 먼저 와야 한다(POST라 겹치지는 않지만 의도를 앞에 둔다).
                        .requestMatchers(HttpMethod.POST, "/api/wiki/pages/*/views").denyAll()
                        // ── 사용자 범위 경로는 GET 허용보다 먼저 닫는다 ──
                        // "GET은 읽기"라는 가정이 이 서비스에서는 성립하지 않는다:
                        // GET /notifications/prefs는 없으면 기본 설정 행을 INSERT 한다(익명 GET이 쓰기).
                        // 나머지도 결과가 로그인 사용자 기준이라 공개 인스턴스에서는 뜻이 없다 —
                        // sub=0의 개인 데이터를 만들거나 보여 주기 전에 경로째로 막는다.
                        // 임포터에게도 열지 않는다(임포터는 문서만 넣는다).
                        .requestMatchers("/api/wiki/notifications", "/api/wiki/notifications/**").denyAll()
                        .requestMatchers("/api/wiki/stars", "/api/wiki/stars/**").denyAll()
                        .requestMatchers("/api/wiki/recent").denyAll()
                        .requestMatchers("/api/wiki/tasks", "/api/wiki/tasks/**").denyAll()
                        .requestMatchers("/api/wiki/migrations", "/api/wiki/migrations/**").denyAll()
                        // 전역 감사 로그는 accessibleSpaces().all()로 전역 관리자를 판정한다 —
                        // PublicReadPermissionClient가 all=true를 주므로 익명에게 열려 버린다.
                        // 스페이스별 감사(/spaces/*/audit)는 ADMIN 판정이라 이미 닫혀 있다.
                        .requestMatchers("/api/wiki/audit/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/api/wiki/**").permitAll()
                        // 라이트 검색 GraphQL — 읽기 질의만 있는 스키마다(mutation 없음).
                        .requestMatchers(HttpMethod.POST, "/graphql").permitAll()
                        // 임포트 토큰이 맞은 요청만 쓰기가 열린다(로컬 루프백 → 임포터 전용).
                        .requestMatchers("/api/wiki/**").hasRole(DocsPrincipalFilter.IMPORTER_ROLE)
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(denied)
                        .accessDeniedHandler(denied));
        return http.build();
    }

    /** org-service를 호출하지 않는다 — 이 인스턴스의 문서는 전부 공개다. */
    @Bean
    PermissionClient docsPermissionClient() {
        return new PublicReadPermissionClient();
    }

    /** 팀 원장이 없다. 페이지 제한의 TEAM 주체는 아무에게도 맞지 않는다(fail-closed). */
    @Bean
    TeamDirectory docsTeamDirectory() {
        return userId -> List.of();
    }

    /** 제한 저장 경로 자체가 막혀 있어 검증할 주체가 오지 않는다 — 통과시킨다. */
    @Bean
    PrincipalDirectory docsPrincipalDirectory() {
        return principals -> {
        };
    }

    /** 조회할 계정도 팀도 없는 인스턴스다. 이관이 돌더라도 아무도 대조되지 않는다(fail-closed). */
    @Bean
    MigrationPrincipalResolver docsMigrationPrincipalResolver() {
        return new UnmappedMigrationPrincipalResolver();
    }

    /**
     * 발행하지 않는 no-op. `platform.events.enabled=false`로 Redis 발행기가 빠진 자리를 채운다 —
     * 이 빈이 없으면 EventRelay가 주입에 실패해 부팅이 깨진다. 공개 문서를 팀 위키 색인 스트림에
     * 흘려보내지 않는 것이 목적이므로 "조용히 버린다"가 맞는 동작이다.
     */
    @Bean
    EventPublisher docsEventPublisher() {
        return event -> {
        };
    }

    /**
     * 거부 응답도 플랫폼 계약(`{"error": …}`)을 지킨다. 인증 실패와 인가 실패를 같은 403으로
     * 합치는 이유: 이 인스턴스에는 로그인이 없어 401을 받아도 사용자가 할 수 있는 일이 없다.
     */
    private static final class DocsDenialHandler implements AccessDeniedHandler, AuthenticationEntryPoint {

        private static final byte[] BODY =
                "{\"error\":\"읽기 전용 문서 인스턴스입니다.\"}".getBytes(StandardCharsets.UTF_8);

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
                throws IOException {
            write(response);
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
                throws IOException {
            write(response);
        }

        private static void write(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(BODY);
        }
    }
}
