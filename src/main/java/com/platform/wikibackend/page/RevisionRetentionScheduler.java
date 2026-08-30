package com.platform.wikibackend.page;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 리비전 보관 정리 시계 — 기본 6시간마다. 테스트 프로필은 끈다(테스트가 prune을 직접 부른다). */
@Component
@ConditionalOnProperty(prefix = "platform.wiki.revisions", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RevisionRetentionScheduler {

    private final RevisionRetentionService retention;

    @Scheduled(fixedDelayString = "${platform.wiki.revisions.interval:PT6H}",
               initialDelayString = "${platform.wiki.revisions.initial-delay:PT10M}")
    public void tick() {
        try {
            retention.prune(Instant.now());
        } catch (RuntimeException e) {
            log.error("리비전 보관 정리 실패", e);
        }
    }
}
