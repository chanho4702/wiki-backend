package com.platform.wikibackend.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 요약 메일 시계 — 기본 매일 09:00(서버 시간). 테스트 프로필은 끈다. */
@Component
@ConditionalOnProperty(prefix = "platform.wiki.mail", name = "digest-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class NotificationDigestScheduler {

    private final NotificationDigestService digest;

    @Scheduled(cron = "${platform.wiki.mail.digest-cron:0 0 9 * * *}")
    public void tick() {
        try {
            digest.run();
        } catch (RuntimeException e) {
            log.error("알림 요약 메일 실패", e);
        }
    }
}
