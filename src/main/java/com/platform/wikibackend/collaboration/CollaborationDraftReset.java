package com.platform.wikibackend.collaboration;

import com.platform.wikibackend.domain.CollaborationDraftMetadata;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.repository.CollaborationDraftMetadataRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

/**
 * 공동 초안 게시({@code commitCollaborationDraft}) 밖의 경로가 page 버전을 올렸을 때의 단일 진입점.
 *
 * 초안의 base는 게시에서만 전진하므로, 일반 저장·복원·이관·작업 토글이 버전을 올리면 초안이 옛 base에
 * 묶여 bootstrap은 옛 기준을 돌려주고 게시는 영영 409가 된다(차단-1). 그래서 같은 트랜잭션에서 초안을
 * 제자리 리셋해 새 버전을 기준으로 다시 시작하게 한다 — 옛 세션의 편집은 세대 가드가 막는다.
 *
 * 잠금 순서는 page → draft다(게시와 같다). 호출자가 page를 {@code findByIdForUpdate}로 먼저 잠그고,
 * {@link Page#edit}로 버전을 올린 **뒤에** 부른다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CollaborationDraftReset {

    /** 빈 Y.Doc update(2바이트). state NOT NULL을 채우기 위한 값이며, reset_pending이라 로드되지 않는다. */
    static final byte[] EMPTY_YDOC_UPDATE = new byte[] {0, 0};

    private final CollaborationDraftMetadataRepository drafts;
    private final CollaborationResetSignal signal;
    private final EntityManager entityManager;

    /** @return 리셋했으면 새 reset_epoch, 초안이 없으면 empty */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> afterExternalWrite(Page page) {
        String room = CollaborationDraftMetadata.room(page.getId());
        Optional<CollaborationDraftMetadata> locked = drafts.findByRoomForUpdate(room);
        if (locked.isEmpty()) return Optional.empty();

        CollaborationDraftMetadata draft = locked.get();
        drafts.resetForExternalWrite(room, page.getVersion().longValue(), EMPTY_YDOC_UPDATE);
        // 네이티브 UPDATE는 영속성 컨텍스트를 모른다 — 같은 트랜잭션의 뒤 읽기가 옛 값을 보지 않게 다시 읽는다.
        entityManager.refresh(draft);
        long resetEpoch = draft.getResetEpoch();
        log.info("공동 초안 리셋(다른 경로 저장): room={} base={} generation={} resetEpoch={}",
                room, draft.getBasePageVersion(), draft.getGeneration(), resetEpoch);

        // 커밋 후에만 알린다 — 롤백된 저장으로 세션을 끊으면 멀쩡한 초안의 사용자만 쫓겨난다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { signal.publish(room, resetEpoch); }
        });
        return Optional.of(resetEpoch);
    }
}
