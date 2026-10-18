package com.platform.wikibackend.grpc;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Locale;

/**
 * 콘텐츠 gRPC 서버 기동 여부 — {@code platform.grpc.enabled}(기본 true) AND {@code SEARCH_MODE}가 lite가 아님.
 *
 * 이 서버의 소비자는 search-service 하나다. lite(기본 설치)에는 search-service가 없으니 권한 검사 없이
 * 전 문서를 주는 서버를 열어 둘 이유가 없다(감사 W-09). 스위치는 플랫폼 설치 옵션 SEARCH_MODE 하나를
 * 따른다(루트 확정 결정 "설치 옵션은 모드 스위치 하나로"). 값이 없으면(그 값을 넘기지 않는 옛 compose)
 * 예전처럼 켠다 — opensearch 모드 배포가 색인 조달을 잃지 않게.
 */
public class WikiGrpcServerCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return enabled(context.getEnvironment());
    }

    static boolean enabled(Environment env) {
        if (!env.getProperty("platform.grpc.enabled", Boolean.class, true)) return false;
        String mode = env.getProperty("platform.search.mode", "");
        return !"lite".equals(mode.trim().toLowerCase(Locale.ROOT));
    }
}
