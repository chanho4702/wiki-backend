package com.platform.wikibackend.config;

/**
 * 내부 서비스 전용 경로(`/internal/**`)의 계약 상수.
 *
 * 토큰 검사 자체는 common-starter의 {@link com.platform.common.security.InternalTokenFilter}가 한다(P-07,
 * 2026-10-18 — 서비스마다 갈라져 있던 사본을 하나로). 여기는 위키만의 것(누구를 대신하는가 헤더, 역할 이름,
 * 거부 문구)만 남긴다.
 */
public final class InternalApi {

    private InternalApi() {
    }

    /** 공유 비밀 헤더 — 플랫폼 표준(스타터와 같은 값). */
    public static final String TOKEN_HEADER = com.platform.common.security.InternalTokenFilter.DEFAULT_HEADER;

    /** 잡 요청자 id — 감사·createdBy 폴백. 값 검증은 컨트롤러가 한다(형식 오류는 403이 아니라 400). */
    public static final String ACTOR_HEADER = "X-Actor-Id";

    public static final String ROLE = "INTERNAL";

    /** 거부 본문 문구 — 예전 위키 필터·거부 핸들러와 같은 값을 유지한다(호출자 계약). */
    public static final String DENIED_MESSAGE = "내부 전용 경로입니다.";
}
