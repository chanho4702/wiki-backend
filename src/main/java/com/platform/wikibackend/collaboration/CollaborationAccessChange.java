package com.platform.wikibackend.collaboration;

import com.platform.wikibackend.domain.CollaborationDraftMetadata;
import com.platform.wikibackend.repository.CollaborationDraftMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;

/**
 * 페이지 접근 범위가 바뀌는 쓰기(휴지통 보내기·이동·페이지 제한 교체)의 공동 편집 후처리 진입점.
 *
 * 공동 초안 행이 있는 room만 골라 커밋 후에 {@link CollaborationRevokeSignal}을 보낸다 — 롤백된 변경으로
 * 세션을 끊지 않기 위해서다. 초안 행이 없으면 열린 세션도 없으므로 보내지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CollaborationAccessChange {

    private final CollaborationDraftMetadataRepository drafts;
    private final CollaborationRevokeSignal signal;

    /** @param pageIds 접근 범위가 바뀐 페이지(서브트리 포함). 변경 **전에** 모은 id여야 한다(휴지통은 조회에서 빠진다). */
    public void afterCommit(Collection<Long> pageIds) {
        if (pageIds.isEmpty()) return;
        List<String> rooms = drafts.findAllById(pageIds.stream().map(CollaborationDraftMetadata::room).toList())
                .stream()
                .map(CollaborationDraftMetadata::getRoom)
                .toList();
        if (rooms.isEmpty()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            signal.publish(rooms);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { signal.publish(rooms); }
        });
    }
}
