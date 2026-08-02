package com.platform.wikibackend.event;

import com.platform.proto.events.v1.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 커밋 후 발행 릴레이. 트랜잭션 문맥이면 afterCommit 훅에, 아니면 즉시.
 * 발행 실패는 WARN 비차단 — 정합의 최종 근거는 DB, 색인은 Wave C 재색인으로 보정(스펙).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventRelay {

    private final EventPublisher publisher;

    public void afterCommit(EventEnvelope event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { safePublish(event); }
            });
        } else {
            safePublish(event);
        }
    }

    private void safePublish(EventEnvelope event) {
        try {
            publisher.publish(event);
        } catch (Exception e) {
            // ERROR인 이유: 발행 실패는 곧 색인 유실이고, 화면은 멀쩡해 보여 아무도 모른다.
            // 실제로 dev가 스트림 미지원 Redis에 붙어 12일간 조용히 전부 유실됐다(2026-08-02).
            // 요청은 계속 성공시킨다 — 정합의 최종 근거는 DB이고 색인은 재색인으로 보정한다(스펙).
            log.error("이벤트 발행 실패 — 색인 유실됨(비차단): type={} id={}",
                    event.getPayloadCase(), event.getEventId(), e);
        }
    }
}
