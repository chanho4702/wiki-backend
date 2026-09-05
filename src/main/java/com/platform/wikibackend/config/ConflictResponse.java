package com.platform.wikibackend.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 엔드포인트가 실제로 409를 낸다는 표시와 그 사유. {@link OpenApiConfig}의 OperationCustomizer가
 * 읽어 문서에 409 응답을 붙인다. org-service와 같은 패턴이다.
 *
 * <p>Swagger의 {@code @ApiResponse}를 직접 쓰지 않는 이유: 오퍼레이션에 {@code @ApiResponse}가 하나라도
 * 붙으면 springdoc이 반환 타입에서 자동으로 만들던 성공 응답(200/201)을 더 이상 넣지 않는다.
 * 성공 응답 스키마를 손으로 다시 적으면 반환 타입이 바뀔 때 조용히 어긋난다.
 *
 * <p>낙관적 락 409(요청 DTO에 {@code expectedVersion}이 있는 PUT)는 이 애너테이션 없이도 붙는다 —
 * 그 사유는 엔드포인트마다 다르지 않아 정본 문구를 그대로 쓴다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConflictResponse {

    /** 화면에 그대로 나갈 수 있는 한국어 사유. */
    String value();

    /**
     * 409 바디 스키마 이름. 비우면 공통 {@code PlatformError}.
     * 이동 영향(MoveImpactException)만 {@code error} 외에 {@code impact}를 더 싣는다.
     */
    String schema() default "";
}
