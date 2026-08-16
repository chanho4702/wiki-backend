package com.platform.wikibackend.attachment;

import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AttachmentReconciliationService {

    private final AttachmentRepository attachments;
    private final PageRepository pages;
    private final AttachmentStorageRouter storage;
    private final EventRelay events;

    /**
     * 만료된 PENDING 행을 최신 페이지 본문과 대조한다. 참조 중이면 확정하고, 아니면 DB commit 뒤
     * 객체를 삭제한다. 한 번에 제한된 batch만 처리해 다중 노드에서도 짧은 트랜잭션을 유지한다.
     */
    @Transactional
    public ReconciliationResult reconcileExpired(Instant cutoff, int batchSize) {
        int safeBatchSize = Math.max(1, Math.min(batchSize, 1_000));
        List<Attachment> candidates = attachments.findExpiredByLifecycleStatus(
                AttachmentLifecycleStatus.PENDING, cutoff, PageRequest.of(0, safeBatchSize));
        int confirmed = 0;
        int deleted = 0;

        for (Attachment attachment : candidates) {
            Optional<Page> page = pages.findById(attachment.getPageId());
            if (page.isPresent()
                    && page.get().getContent().contains(AttachmentReferences.inlineUrl(attachment.getId()))) {
                attachment.confirm();
                events.afterCommit(WikiEvents.attachmentAdded(
                        attachment.getUploadedBy(), attachment, page.get().getSpaceId()));
                confirmed++;
                continue;
            }

            attachments.delete(attachment);
            storage.deleteAfterCommit(attachment.getStorageBackend(), attachment.getStorageBucket(),
                    attachment.getStorageKey(), attachment.getStorageVersion());
            deleted++;
        }
        return new ReconciliationResult(candidates.size(), confirmed, deleted);
    }

    public record ReconciliationResult(int examined, int confirmed, int deleted) {
    }
}
