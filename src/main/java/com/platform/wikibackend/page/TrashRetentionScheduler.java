package com.platform.wikibackend.page;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 휴지통 보존 기간(W21-1).
 *
 * 기본값은 **자동 삭제 없음**(`retention: PT0S`)이다. 사용자가 버린 문서를 아무도 지시하지
 * 않았는데 서비스가 스스로 영구 삭제하는 것은 되돌릴 수 없는 동작이라, 운영자가 기간을
 * 명시적으로 켜야 동작하게 둔다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "platform.wiki.trash.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class TrashRetentionScheduler {

    private final TrashService trash;

    @Value("${platform.wiki.trash.retention:PT0S}")
    private Duration retention;

    @Scheduled(
            fixedDelayString = "${platform.wiki.trash.interval:PT6H}",
            initialDelayString = "${platform.wiki.trash.initial-delay:PT10M}")
    public void purgeExpired() {
        if (retention.isZero() || retention.isNegative()) return; // 보존 무기한 — 아무것도 하지 않는다
        try {
            trash.purgeExpired(Instant.now().minus(retention));
        } catch (RuntimeException e) {
            log.error("휴지통 보존 기간 정리 실패", e);
        }
    }
}
