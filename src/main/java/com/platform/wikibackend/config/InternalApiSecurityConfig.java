package com.platform.wikibackend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
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

/**
 * 내부 서비스 전용 경로(`/internal/**`)의 보안 체인 — 이관 엔진(migration-service)이 부르는
 * import API가 여기 산다.
 *
 * {@link SecurityConfig}의 JWT 체인과 **갈라놓는다**. 그쪽에 얹으면 이 경로도 JWKS로 검증되는
 * 사용자 토큰을 요구하게 되는데, 부르는 쪽은 사람이 아니라 잡 워커다. `@Order(0)`으로 먼저
 * 서고 `securityMatcher`로 `/internal/**`만 가져가므로, JWT 체인은 이 경로를 보지 않는다.
 *
 * docs 프로필에는 이 체인을 만들지 않는다 — 거기서는 {@link DocsSecurityConfig}가
 * `/internal/**`을 명시적으로 denyAll 한다.
 */
@Configuration
@Profile("!docs")
public class InternalApiSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain internalFilterChain(
            HttpSecurity http,
            @Value("${platform.wiki.internal-token:}") String internalToken) throws Exception {
        InternalDenialHandler denied = new InternalDenialHandler();
        http
                .securityMatcher("/internal/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                // 인가 판정 직전에 주체를 심는다(DocsPrincipalFilter와 같은 자리) — 빈으로 노출하면
                // Boot가 서블릿 필터로 한 번 더 등록해 모든 경로에서 돈다.
                // 토큰 검사는 스타터 필터(trim·fail-closed·상수시간 비교) — 틀리면 그 자리에서 403 {"error":…}.
                // 통과한 요청에만 주체를 심는다. 둘 다 빈으로 노출하지 않는다(Boot가 서블릿 필터로 한 번 더 등록한다).
                .addFilterBefore(new com.platform.common.security.InternalTokenFilter(
                        InternalApi.TOKEN_HEADER, internalToken, InternalApi.DENIED_MESSAGE), AuthorizationFilter.class)
                .addFilterBefore(new InternalPrincipalFilter(), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(InternalApi.ROLE))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(denied)
                        .accessDeniedHandler(denied));
        return http.build();
    }

    /**
     * 인증 실패와 인가 실패를 같은 403으로 합친다 — 내부 호출자에게 "토큰이 없어서인지 틀려서인지"를
     * 알려 줄 이유가 없다. 본문은 플랫폼 오류 계약(`{"error": …}`)을 지킨다.
     */
    private static final class InternalDenialHandler implements AccessDeniedHandler, AuthenticationEntryPoint {

        private static final byte[] BODY =
                ("{\"error\":\"" + InternalApi.DENIED_MESSAGE + "\"}").getBytes(StandardCharsets.UTF_8);

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
