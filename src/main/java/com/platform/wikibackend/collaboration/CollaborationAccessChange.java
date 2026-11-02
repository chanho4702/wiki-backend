package com.platform.wikibackend.collaboration;

import com.platform.wikibackend.domain.CollaborationDraftMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;

/**
 * 페이지 접근 범위가 바뀌는 쓰기(휴지통 보내기·이동·페이지 제한 교체)의 공동 편집 후처리 진입점.
 *
 * 영향받은 페이지 room 전부에 커밋 후 {@link CollaborationRevokeSignal}을 보낸다 — 롤백된 변경으로 세션을 끊지
 * 않기 위해서다. 공동 초안 행 유무로 거르지 않는다: 이 트랜잭션이 행을 확인한 뒤 커밋되기 전에 새로 열린 세션도
 * 신호를 받아야 한다(Codex P1). 열린 세션이 없는 room은 collaboration-service가 회수 시각만 잠시 기억한다.
 */
@Component
@RequiredArgsConstructor
public class CollaborationAccessChange {

    private final CollaborationRevokeSignal signal;

    /** @param pageIds 접근 범위가 바뀐 페이지(서브트리 포함). 변경 **전에** 모은 id여야 한다(휴지통은 조회에서 빠진다). */
    public void afterCommit(Collection<Long> pageIds) {
        if (pageIds.isEmpty()) return;
        List<String> rooms = pageIds.stream().distinct().map(CollaborationDraftMetadata::room).toList();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            signal.publish(rooms);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { signal.publish(rooms); }
        });
    }
}
