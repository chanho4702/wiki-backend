package com.platform.wikibackend.attachment;

import com.platform.wikibackend.attachment.dto.AttachmentResponse;
import com.platform.wikibackend.attachment.dto.AttachmentVersionResponse;
import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.common.UnsafeInlineMediaTypeException;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.page.PageService;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.space.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class AttachmentService {

    private final AttachmentRepository attachments;
    private final PageService pages;
    private final SpaceService spaces;
    private final com.platform.wikibackend.permission.EffectivePermissionService effective;
    private final AttachmentStorageRouter storage;
    private final EventRelay events;
    private final com.platform.wikibackend.repository.AttachmentVersionRepository versions;

    public AttachmentResponse upload(long userId, long pageId, MultipartFile file) {
        return upload(userId, pageId, file, false);
    }

    public AttachmentResponse upload(long userId, long pageId, MultipartFile file, boolean pending) {
        Page page = pages.getOwned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, page);
        try {
            String contentType;
            try (InputStream probe = file.getInputStream()) {
                contentType = AttachmentMediaTypes.detect(probe);
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StoredObject stored;
            try (InputStream raw = file.getInputStream(); DigestInputStream input = new DigestInputStream(raw, digest)) {
                stored = storage.store(input, file.getSize(), contentType);
            }
            storage.deleteAfterRollback(stored);

            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed";
            String checksum = HexFormat.of().formatHex(digest.digest());
            AttachmentLifecycleStatus lifecycleStatus = pending
                    ? AttachmentLifecycleStatus.PENDING
                    : AttachmentLifecycleStatus.CONFIRMED;

            /*
             * 같은 이름이 이미 있으면 새 행을 만들지 않고 그 행을 갈아끼운다(W23).
             *
             * 본문의 인라인 참조는 첨부 id로 걸려 있다 — 새 행을 만들면 id가 달라져 문서에는
             * 옛 파일이 계속 보인다. 갈아끼우면 문서 어디에 박혀 있든 새 파일이 나온다.
             *
             * PENDING 업로드(편집 중 임시)는 대상이 아니다. 아직 문서에 실린 파일이 아니라
             * 갈아끼울 "현재"가 없고, 편집을 취소하면 사라져야 하기 때문이다.
             */
            Attachment existing = pending ? null : attachments
                    .findByPageIdAndFilenameAndLifecycleStatus(
                            pageId, filename, AttachmentLifecycleStatus.CONFIRMED)
                    .orElse(null);
            if (existing != null) {
                versions.save(com.platform.wikibackend.domain.AttachmentVersion.snapshotOf(existing));
                existing.replaceWith(contentType, file.getSize(), stored, checksum, userId);
                Attachment replaced = attachments.save(existing);
                events.afterCommit(WikiEvents.attachmentAdded(userId, replaced, page.getSpaceId()));
                return AttachmentResponse.from(replaced);
            }

            Attachment saved = attachments.save(
                    Attachment.of(pageId, filename, contentType, file.getSize(), stored, checksum, userId,
                            lifecycleStatus));
            if (!pending) {
                events.afterCommit(WikiEvents.attachmentAdded(userId, saved, page.getSpaceId()));
            }
            return AttachmentResponse.from(saved);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 스트림 읽기 실패", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(long userId, long pageId) {
        Page page = pages.getOwned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
        return attachments.findByPageId(pageId).stream().map(AttachmentResponse::from).toList();
    }

    /**
     * 지난 버전 목록 — 최신 버전이 먼저. 현재 내용은 여기 없다(attachment 행이 곧 현재다).
     */
    @Transactional(readOnly = true)
    public List<AttachmentVersionResponse> versions(long userId, long attachmentId) {
        Attachment a = requireViewable(userId, attachmentId);
        return versions.findByAttachmentIdOrderByVersionDesc(a.getId()).stream()
                .map(AttachmentVersionResponse::from)
                .toList();
    }

    /** 지난 버전 내려받기 — 미리보기 없이 파일로만 준다(옛 파일을 문서에 다시 심을 이유는 없다). */
    @Transactional(readOnly = true)
    public DownloadItem downloadVersion(long userId, long attachmentId, int version) {
        Attachment a = requireViewable(userId, attachmentId);
        com.platform.wikibackend.domain.AttachmentVersion v = versions
                .findByAttachmentIdAndVersion(a.getId(), version)
                .orElseThrow(() -> new NotFoundException("첨부 버전 없음: " + attachmentId + " v" + version));
        return new DownloadItem(a, v.getContentType(), v.getSizeBytes(),
                storage.open(v.getStorageBackend(), v.getStorageBucket(),
                        v.getStorageKey(), v.getStorageVersion()));
    }

    /**
     * 지난 버전을 현재로 되돌린다.
     *
     * 옛 저장 객체를 그대로 현재 좌표로 올리는 대신 **새 버전으로 쌓는다**. 그래야 되돌린 사실이
     * 이력에 남고, 같은 객체를 두 행이 가리켜 한쪽을 지울 때 다른 쪽이 깨지는 일이 없다.
     * (저장 객체는 복사하지 않는다 — 같은 바이트라 복사할 이유가 없다.)
     */
    public AttachmentResponse restoreVersion(long userId, long attachmentId, int version) {
        Attachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("첨부 없음: " + attachmentId));
        Page page = pages.getOwned(a.getPageId());
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, page);

        com.platform.wikibackend.domain.AttachmentVersion target = versions
                .findByAttachmentIdAndVersion(a.getId(), version)
                .orElseThrow(() -> new NotFoundException("첨부 버전 없음: " + attachmentId + " v" + version));

        versions.save(com.platform.wikibackend.domain.AttachmentVersion.snapshotOf(a));
        a.replaceWith(target.getContentType(), target.getSizeBytes(), target.storedObject(),
                target.getChecksumSha256(), userId);
        Attachment restored = attachments.save(a);
        events.afterCommit(WikiEvents.attachmentAdded(userId, restored, page.getSpaceId()));
        return AttachmentResponse.from(restored);
    }

    private Attachment requireViewable(long userId, long attachmentId) {
        Attachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("첨부 없음: " + attachmentId));
        Page page = pages.getOwned(a.getPageId());
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
        return a;
    }

    /** 다운로드용 — [메타, 리소스] 쌍. */
    @Transactional(readOnly = true)
    public DownloadItem download(long userId, long attachmentId) {
        Attachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("첨부 없음: " + attachmentId));
        Page page = pages.getOwned(a.getPageId());
        spaces.require(userId, page.getSpaceId(), WikiAction.VIEW);
        effective.requireView(userId, page);
        return new DownloadItem(a, storage.open(a.getStorageBackend(), a.getStorageBucket(),
                a.getStorageKey(), a.getStorageVersion()));
    }

    /**
     * 페이지 저장과 별도 요청이므로 서버의 최신 본문을 다시 검사한다. 클라이언트가 임의 ID를
     * 확정하거나 저장 실패한 이미지를 장기 보존 상태로 바꾸지 못한다.
     */
    public void confirm(long userId, long pageId, List<Long> attachmentIds) {
        Page page = pages.getOwned(pageId);
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, page);
        if (attachmentIds == null || attachmentIds.isEmpty()) return;

        Set<Long> uniqueIds = new LinkedHashSet<>(attachmentIds);
        List<Attachment> found = attachments.findAllById(uniqueIds);
        if (found.size() != uniqueIds.size()) {
            throw new NotFoundException("확정할 첨부 중 존재하지 않는 항목이 있습니다");
        }

        for (Attachment attachment : found) {
            if (!attachment.getPageId().equals(pageId)) {
                throw new IllegalArgumentException("다른 페이지의 첨부는 확정할 수 없습니다");
            }
            String durableUrl = AttachmentReferences.inlineUrl(attachment.getId());
            if (!page.getContent().contains(durableUrl)) {
                throw new IllegalArgumentException("본문에서 참조하지 않는 첨부는 확정할 수 없습니다: "
                        + attachment.getId());
            }
        }

        for (Attachment attachment : found) {
            if (attachment.getLifecycleStatus() == AttachmentLifecycleStatus.PENDING) {
                attachment.confirm();
                events.afterCommit(WikiEvents.attachmentAdded(userId, attachment, page.getSpaceId()));
            }
        }
    }

    @Transactional(readOnly = true)
    public DownloadItem inline(long userId, long attachmentId) {
        DownloadItem item = download(userId, attachmentId);
        if (!AttachmentMediaTypes.isSafeInline(item.meta().getContentType())) {
            throw new UnsafeInlineMediaTypeException("인라인 표시를 허용하지 않는 첨부 형식입니다");
        }
        return item;
    }

    public void delete(long userId, long attachmentId) {
        Attachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("첨부 없음: " + attachmentId));
        Page page = pages.getOwned(a.getPageId());
        spaces.require(userId, page.getSpaceId(), WikiAction.EDIT);
        effective.requireEdit(userId, page);
        /*
         * 지난 버전을 행과 파일 양쪽에서 치운다.
         *
         * 행은 DB의 FK CASCADE에 맡기지 않는다 — JPA 매핑에 관계가 없어서(컬럼만 들고 있다)
         * 스키마를 Hibernate가 만드는 환경에는 그 제약이 없다. 파일은 어차피 여기서 지워야 한다.
         */
        for (com.platform.wikibackend.domain.AttachmentVersion v
                : versions.findByAttachmentIdOrderByVersionDesc(a.getId())) {
            storage.deleteAfterCommit(v.getStorageBackend(), v.getStorageBucket(),
                    v.getStorageKey(), v.getStorageVersion());
        }
        versions.deleteByAttachmentId(a.getId());
        attachments.delete(a);
        storage.deleteAfterCommit(a.getStorageBackend(), a.getStorageBucket(),
                a.getStorageKey(), a.getStorageVersion());
        // PENDING은 아직 AttachmentAdded를 발행하지 않은 임시 객체다. 취소 정리에 Deleted만 내보내면
        // 검색 소비자가 보지 못한 엔티티의 삭제 이벤트를 받게 되므로 확정 첨부만 발행한다.
        if (a.getLifecycleStatus() == AttachmentLifecycleStatus.CONFIRMED) {
            events.afterCommit(WikiEvents.attachmentDeleted(userId, a, page.getSpaceId()));
        }
    }

    /**
     * 내려보낼 것 — 메타는 현재 첨부지만 내용 정보(타입·크기)는 **그 버전의 것**이다.
     * 지난 버전을 현재 메타로 내려보내면 크기가 어긋나 브라우저가 잘린 파일을 받는다.
     */
    public record DownloadItem(Attachment meta, String contentType, long sizeBytes, Resource resource) {
        public DownloadItem(Attachment meta, Resource resource) {
            this(meta, meta.getContentType(), meta.getSizeBytes(), resource);
        }
    }
}
