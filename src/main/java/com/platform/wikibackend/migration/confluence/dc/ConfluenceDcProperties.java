package com.platform.wikibackend.migration.confluence.dc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * DC 추출 파라미터.
 *
 * readTimeout은 worker lease(기본 5분)보다 짧아야 한다 — 더 길면 handler가 아직 응답을 기다리는
 * 동안 다른 노드가 같은 item을 회수해 같은 페이지를 두 번 긁는다.
 */
@Component
public record ConfluenceDcProperties(
        @Value("${platform.wiki.migration.dc.connect-timeout:PT10S}") Duration connectTimeout,
        @Value("${platform.wiki.migration.dc.read-timeout:PT60S}") Duration readTimeout,
        @Value("${platform.wiki.migration.dc.max-pages:5000}") int maxPages,
        @Value("${platform.wiki.migration.dc.page-size:100}") int pageSize) {

    public ConfluenceDcProperties {
        if (maxPages < 1) {
            throw new IllegalArgumentException("maxPages must be at least 1");
        }
        if (pageSize < 1 || pageSize > 200) {
            // DC는 limit 상한을 사이트 설정으로 두는데, 200을 넘겨 보내면 조용히 잘려 페이지네이션
            // 종료 판정(size < limit)이 영원히 성립하지 않는다.
            throw new IllegalArgumentException("pageSize must be between 1 and 200");
        }
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("timeouts must be positive");
        }
    }
}
