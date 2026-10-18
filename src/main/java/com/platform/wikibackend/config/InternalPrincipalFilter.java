package com.platform.wikibackend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 내부 체인의 주체 심기 — 스타터 {@link com.platform.common.security.InternalTokenFilter}가 토큰을 통과시킨
 * 요청에만 도달한다(그 필터 뒤에 놓는다). 체인의 {@code hasRole(INTERNAL)}이 두 겹째 방어다.
 *
 * 빈으로 등록하지 않는다 — Boot가 서블릿 필터로 한 번 더 등록해 모든 경로에서 돈다.
 */
public class InternalPrincipalFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(synthetic(),
                List.of(new SimpleGrantedAuthority("ROLE_" + InternalApi.ROLE))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    /**
     * 서명 없는 합성 토큰. 이 체인에는 JwtDecoder가 없으므로 검증을 거치지 않는다.
     * sub는 사람 id가 아니다 — 실제 주체는 {@link InternalApi#ACTOR_HEADER}이고, 헤더가 없거나 숫자가
     * 아닐 때 403(인증 실패)이 아니라 400이어야 하기 때문에 여기에 넣지 않는다.
     */
    private static Jwt synthetic() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("internal")
                .header("alg", "none")
                .subject("internal")
                .claim("name", "internal")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
    }
}
