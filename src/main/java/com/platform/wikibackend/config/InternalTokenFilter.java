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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

/**
 * 내부 서비스 전용 경로(`/internal/**`)의 공유 비밀 인증.
 *
 * 이 경로는 게이트웨이·nginx가 라우팅하지 않는다 — 클러스터 밖에서는 닿지 않고, 안에서는
 * migration-service만 부른다. JWT를 쓰지 않는 이유: 부르는 쪽이 사람이 아니라 잡 워커라
 * 갱신할 사용자 토큰이 없고, 잡 요청자의 토큰을 워커가 오래 들고 있는 편이 더 위험하다.
 * 대신 "누구를 대신해 쓰는가"는 {@link #ACTOR_HEADER}로 따로 받아 컨트롤러가 검증한다.
 *
 * 토큰 프로퍼티가 비어 있으면 아무도 통과하지 못한다(docs 임포터와 같은 규칙) — 설정을
 * 깜빡한 인스턴스가 열린 채로 뜨는 것이 이 필터에서 가장 나쁜 실패다.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Internal-Token";

    /** 잡 요청자 id — 감사·createdBy 폴백. 값 검증은 컨트롤러가 한다(형식 오류는 403이 아니라 400). */
    public static final String ACTOR_HEADER = "X-Actor-Id";

    public static final String ROLE = "INTERNAL";

    private final byte[] token;

    public InternalTokenFilter(String token) {
        this.token = (token == null || token.isBlank()) ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (matches(request)) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new JwtAuthenticationToken(synthetic(),
                    List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
            SecurityContextHolder.setContext(context);
        }
        // 맞지 않으면 주체를 심지 않는다 — 체인의 hasRole이 403으로 끊는다.
        chain.doFilter(request, response);
    }

    private boolean matches(HttpServletRequest request) {
        if (token == null) {
            return false; // 토큰 미설정 = 내부 경로도 닫힘
        }
        String presented = request.getHeader(TOKEN_HEADER);
        if (presented == null) {
            return false;
        }
        // 상수 시간 비교 — 내부망 전용이라도 토큰을 길이·접두로 흘리지 않는다.
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token);
    }

    /**
     * 서명 없는 합성 토큰. 이 체인에는 JwtDecoder가 없으므로 검증을 거치지 않는다.
     * sub는 사람 id가 아니다 — 실제 주체는 {@link #ACTOR_HEADER}이고, 여기에 그 값을 넣지
     * 않는 이유는 헤더가 없거나 숫자가 아닐 때 403(인증 실패)이 아니라 400이어야 하기 때문이다.
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
