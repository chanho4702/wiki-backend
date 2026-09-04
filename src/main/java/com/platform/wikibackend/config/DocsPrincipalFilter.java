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
 * 공개 문서 인스턴스(docs)의 합성 주체 주입기.
 *
 * 왜 필요한가: 이 서비스의 컨트롤러는 예외 없이 `@AuthenticationPrincipal Jwt`를 받아
 * `Long.parseLong(jwt.getSubject())`로 사용자 ID를 얻는다(`SpaceController.userId`). docs에는
 * 로그인이 없으므로 주체를 심어 주지 않으면 익명 GET 하나에도 NPE가 난다. 컨트롤러 전부를
 * "주체가 없을 수도 있다"로 고치는 대신, 프로필 전용 필터 하나로 경계를 좁힌다.
 *
 * 익명은 `sub=0` — org-service에 존재하지 않는 사용자 ID다. 설령 권한 판정이 실수로 org로
 * 흘러가도 아무 권한이 붙지 않는다(구조적 안전판).
 *
 * 임포트 토큰이 맞으면 `sub=1`(importer). 쓰기 경로는 이 주체에게만 열린다
 * ({@link DocsSecurityConfig} 의 필터 체인이 `ROLE_DOCS_IMPORTER`를 요구한다).
 * 토큰 프로퍼티가 비어 있으면 임포터 판정은 절대 성립하지 않는다 — 헤더를 흉내 내도 소용없다.
 */
public class DocsPrincipalFilter extends OncePerRequestFilter {

    /** nginx가 공개 경로에서 이 헤더를 지운다 — 외부에서 실어 보낼 수 없다. */
    public static final String IMPORT_TOKEN_HEADER = "X-Docs-Import-Token";
    public static final String IMPORTER_ROLE = "DOCS_IMPORTER";
    public static final String READER_ROLE = "DOCS_READER";
    /** org-service에 없는 ID — 권한이 실수로 붙을 수 없다. */
    public static final long READER_USER_ID = 0L;
    public static final long IMPORTER_USER_ID = 1L;

    private final byte[] importToken;

    public DocsPrincipalFilter(String importToken) {
        this.importToken = (importToken == null || importToken.isBlank())
                ? null
                : importToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean importer = isImporter(request);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(
                synthetic(importer),
                List.of(new SimpleGrantedAuthority("ROLE_" + (importer ? IMPORTER_ROLE : READER_ROLE)))));
        SecurityContextHolder.setContext(context);
        // 컨텍스트 정리는 SecurityContextHolderFilter가 체인 종료 시 맡는다(이 필터는 그 뒤에 선다).
        chain.doFilter(request, response);
    }

    private boolean isImporter(HttpServletRequest request) {
        if (importToken == null) return false;   // 토큰 미설정 = 임포트 경로도 닫힘
        String presented = request.getHeader(IMPORT_TOKEN_HEADER);
        if (presented == null) return false;
        // 상수 시간 비교 — 루프백 전용이라도 토큰을 길이·접두로 흘리지 않는다
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), importToken);
    }

    /**
     * 서명 없는 합성 토큰. 검증기를 거치지 않으므로(docs 프로필은 JwtDecoder 자체가 없다)
     * 값은 컨트롤러가 읽는 `sub`·`name`만 채운다.
     */
    private static Jwt synthetic(boolean importer) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("docs")
                .header("alg", "none")
                .subject(String.valueOf(importer ? IMPORTER_USER_ID : READER_USER_ID))
                .claim("name", importer ? "importer" : "docs")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
    }
}
