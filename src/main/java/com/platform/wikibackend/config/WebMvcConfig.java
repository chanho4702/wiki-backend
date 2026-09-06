package com.platform.wikibackend.config;

import com.platform.wikibackend.security.AccountStatusInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 계정 상태 게이트를 위키 REST 표면 전체에 건다. 사용자용 REST는 전부 {@code /api/wiki/**}이므로 한
 * 패턴으로 덮인다. 나머지는 의도적으로 뺀다.
 *
 * <ul>
 *   <li><b>{@code /actuator/**}</b> — 상태를 확인하려고 org를 부르는 게이트가 헬스체크까지 org에 묶으면
 *       org가 죽을 때 위키도 죽은 것으로 보고된다(게이트웨이 상태판이 오진한다).</li>
 *   <li><b>{@code /internal/**}</b> — 이관 엔진이 부르는 import API다. 호출자가 사람이 아니라 잡 워커라
 *       {@link InternalApiSecurityConfig}가 JWT가 아닌 토큰 주체를 심는다. 계정 상태를 물을 대상이 없다
 *       (게이트가 JWT sub만 보므로 걸려도 통과하지만, 경로 자체를 빼서 org 왕복을 만들지 않는다).</li>
 *   <li><b>{@code /graphql}·{@code /v3/api-docs}</b> — 전자는 검색 서비스가 쓰는 읽기 질의 표면이고
 *       후자는 토큰 없는 스펙 조회다. 둘 다 사용자 계정 격리의 대상이 아니다.</li>
 * </ul>
 *
 * <p>{@code docs} 프로필(공개 문서 인스턴스)에는 이 구성 자체를 만들지 않는다 — 거기에는 org 채널도
 * 로그인도 없어 {@link AccountStatusInterceptor} 빈이 존재하지 않는다.
 *
 * <p>인터셉터를 고른 이유: 필터에서 던진 예외는 {@code @RestControllerAdvice}가 잡지 못해 오류 계약
 * ({@code {"error": ...}})을 손으로 다시 써야 한다. 인터셉터의 예외는 평소 경로를 그대로 탄다.
 */
@Configuration
@Profile("!docs")
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AccountStatusInterceptor accountStatus;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accountStatus).addPathPatterns("/api/wiki/**");
    }
}
