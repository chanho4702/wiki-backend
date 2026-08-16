package com.platform.wikibackend.attachment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "platform.wiki.attachment-reconciliation.enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class AttachmentReconciliationScheduler {

    private final AttachmentReconciliationService reconciliation;

    @Value("${platform.wiki.attachment-reconciliation.pending-retention:PT24H}")
    private Duration pendingRetention;

    @Value("${platform.wiki.attachment-reconciliation.batch-size:100}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${platform.wiki.attachment-reconciliation.interval:PT1H}",
            initialDelayString = "${platform.wiki.attachment-reconciliation.initial-delay:PT5M}")
    public void reconcile() {
        try {
            var result = reconciliation.reconcileExpired(Instant.now().minus(pendingRetention), batchSize);
            if (result.examined() > 0) {
                log.info("첨부 pending reconciliation 완료: examined={}, confirmed={}, deleted={}",
                        result.examined(), result.confirmed(), result.deleted());
            }
        } catch (RuntimeException e) {
            log.error("첨부 pending reconciliation 실패", e);
        }
    }
}
