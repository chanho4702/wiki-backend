package com.platform.wikibackend.attachment;

import com.platform.wikibackend.attachment.dto.AttachmentResponse;
import com.platform.wikibackend.attachment.dto.AttachmentVersionResponse;
import com.platform.common.error.NotFoundException;
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
    private final com.platform.wikibackend.audit.AuditService audit;

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

    /**
     * 이관용 스테이징(W29 M2) — 바이트를 저장소에 넣고 좌표만 돌려준다. 아직 어떤 페이지에도 붙지 않는다.
     *
     * MEDIA_COPY 단계는 RESOLVE보다 먼저 돌아 대상 페이지가 아직 없다. 그래서 파일을 먼저 받아 두고
     * 페이지가 생긴 뒤 {@link #registerStored}가 그 객체를 **그대로 가리킨다** — 두 번 올리지 않는다.
     *
     * 트랜잭션을 열지 않는다. 저장소 쓰기는 롤백되지 않는 외부 작업이고, 여기서 커넥션을 잡고 있으면
     * 100MB 파일 하나가 커넥션 풀을 그만큼 붙든다. 실패한 스테이징 객체는 고아로 남지만, 그것이
     * 재시도마다 원본을 다시 긁는 것보다 싸다.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public StagedObject stageImported(byte[] content) {
        try {
            String contentType;
            try (InputStream probe = new java.io.ByteArrayInputStream(content)) {
                contentType = AttachmentMediaTypes.detect(probe);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String checksum = HexFormat.of().formatHex(digest.digest(content));
            StoredObject stored;
            try (InputStream input = new java.io.ByteArrayInputStream(content)) {
                stored = storage.store(input, content.length, contentType);
            }
            return new StagedObject(stored, contentType, checksum, content.length);
        } catch (IOException e) {
            throw new UncheckedIOException("이관 첨부 스테이징 실패", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    /**
     * 이미 저장소에 있는 객체를 첨부 레코드로 등록한다(W29 M2 내부 경로).
     *
     * upload와 다른 점 세 가지, 전부 의도한 것이다.
     * 1. **권한 검사를 하지 않는다** — 부르는 쪽은 잡 요청자(대상 스페이스 ADMIN)를 대신하는 워커이고,
     *    검사 대상인 페이지는 방금 그 워커가 만든 것이다.
     * 2. **바이트를 다시 올리지 않는다** — 스테이징 객체를 그대로 가리킨다. 같은 파일을 두 벌 두면
     *    100페이지짜리 스페이스에서 저장소가 두 배가 된다.
     * 3. **감사 로그를 남기지 않는다** — 수백 건의 이관을 사람이 올린 것처럼 기록하면 감사 로그가
     *    쓸모없어진다. 이관 자체의 기록은 migration_job이 들고 있다.
     *
     * 같은 이름이 이미 있으면 W23 규칙(같은 이름 재업로드 = 새 버전)을 그대로 타되, checksum이 같으면
     * 아무것도 하지 않는다 — 재이관이 같은 파일로 버전만 쌓는 것을 막는다.
     */
    public long registerStored(long userId, long pageId, String filename, String contentType,
                               long sizeBytes, String checksum, StoredObject stored) {
        Page page = pages.getOwned(pageId);
        Attachment existing = attachments
                .findByPageIdAndFilenameAndLifecycleStatus(pageId, filename,
                        AttachmentLifecycleStatus.CONFIRMED)
                .orElse(null);
        if (existing != null) {
            if (checksum != null && checksum.equals(existing.getChecksumSha256())) {
                return existing.getId();
            }
            versions.save(com.platform.wikibackend.domain.AttachmentVersion.snapshotOf(existing));
            existing.replaceWith(contentType, sizeBytes, stored, checksum, userId);
            Attachment replaced = attachments.save(existing);
            events.afterCommit(WikiEvents.attachmentAdded(userId, replaced, page.getSpaceId()));
            return replaced.getId();
        }
        Attachment saved = attachments.save(Attachment.of(pageId, filename, contentType, sizeBytes,
                stored, checksum, userId, AttachmentLifecycleStatus.CONFIRMED));
        events.afterCommit(WikiEvents.attachmentAdded(userId, saved, page.getSpaceId()));
        return saved.getId();
    }

    /** 스테이징 결과 — 아직 어떤 페이지에도 붙지 않은 저장 객체와 그 지문. */
    public record StagedObject(StoredObject stored, String contentType, String checksum, long sizeBytes) {
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
        // 지우기 전에 남긴다 — 지운 뒤에는 파일명을 읽을 수 없다.
        audit.record(page.getSpaceId(), userId,
                com.platform.wikibackend.domain.AuditAction.ATTACHMENT_DELETED,
                "ATTACHMENT", a.getId(), a.getFilename(), "문서: " + page.getTitle());
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
