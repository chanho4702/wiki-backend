package com.platform.wikibackend.grpc;

import com.platform.proto.wiki.v1.AttachmentMeta;
import com.platform.proto.wiki.v1.GetPageContentRequest;
import com.platform.proto.wiki.v1.ListAttachmentsRequest;
import com.platform.proto.wiki.v1.ListPageContentsRequest;
import com.platform.proto.wiki.v1.PageContent;
import com.platform.proto.wiki.v1.WikiContentServiceGrpc;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.repository.AttachmentIndexRow;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 색인기(search-service) 전용 콘텐츠 조달 창구.
 *
 * 권한을 검사하지 않는다 — 호출자가 사용자가 아니라 시스템이기 때문이다.
 * 그래서 이 포트는 컨테이너 내부망에만 노출한다(compose에서 호스트 포트를 열지 않는다).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WikiContentGrpcService extends WikiContentServiceGrpc.WikiContentServiceImplBase {

    /** 백필 스트림의 한 번 조회 크기. 전량을 메모리에 올리지 않으려는 값. */
    private static final int BATCH = 200;

    private final PageRepository pageRepository;
    private final SpaceRepository spaceRepository;
    private final AttachmentRepository attachmentRepository;

    @Override
    @Transactional(readOnly = true)
    public void getPageContent(GetPageContentRequest request, StreamObserver<PageContent> observer) {
        Optional<Page> page = pageRepository.findById(request.getPageId());
        if (page.isEmpty()) {
            // 색인기는 이걸 "이미 지워졌다"로 읽고 색인에서 뺀다 — 삭제 이벤트와 조회가 경합해도 수렴한다
            observer.onError(Status.NOT_FOUND
                    .withDescription("page not found: " + request.getPageId())
                    .asRuntimeException());
            return;
        }
        Optional<Space> space = spaceRepository.findById(page.get().getSpaceId());
        if (space.isEmpty()) {
            observer.onError(Status.NOT_FOUND
                    .withDescription("space not found for page: " + request.getPageId())
                    .asRuntimeException());
            return;
        }
        observer.onNext(toProto(page.get(), space.get()));
        observer.onCompleted();
    }

    @Override
    @Transactional(readOnly = true)
    public void listPageContents(ListPageContentsRequest request, StreamObserver<PageContent> observer) {
        long spaceFilter = request.getSpaceId();
        Map<Long, Space> spaces = new HashMap<>();
        long cursor = 0L;
        while (true) {
            List<Page> batch = spaceFilter == 0L
                    ? pageRepository.findByIdGreaterThanOrderByIdAsc(cursor, Limit.of(BATCH))
                    : pageRepository.findBySpaceIdAndIdGreaterThanOrderByIdAsc(spaceFilter, cursor, Limit.of(BATCH));
            if (batch.isEmpty()) break;
            for (Page p : batch) {
                Space s = spaces.computeIfAbsent(p.getSpaceId(),
                        id -> spaceRepository.findById(id).orElse(null));
                if (s == null) {
                    // 스페이스가 사라진 고아 페이지 — 색인할 표시명이 없으니 건너뛴다(로그로 남긴다)
                    log.warn("백필 중 고아 페이지 건너뜀: page={} space={}", p.getId(), p.getSpaceId());
                    continue;
                }
                observer.onNext(toProto(p, s));
            }
            cursor = batch.getLast().getId();
        }
        observer.onCompleted();
    }

    @Override
    @Transactional(readOnly = true)
    public void listAttachments(ListAttachmentsRequest request, StreamObserver<AttachmentMeta> observer) {
        long cursor = 0L;
        while (true) {
            List<AttachmentIndexRow> batch =
                    attachmentRepository.findForIndexing(cursor, request.getSpaceId(), Limit.of(BATCH));
            if (batch.isEmpty()) break;
            for (AttachmentIndexRow a : batch) {
                observer.onNext(AttachmentMeta.newBuilder()
                        .setAttachmentId(a.attachmentId())
                        .setPageId(a.pageId())
                        .setSpaceId(a.spaceId())
                        .setFilename(a.filename())
                        .setContentType(a.contentType())
                        .setSizeBytes(a.sizeBytes())
                        .setUploadedBy(a.uploadedBy())
                        .setCreatedAt(a.createdAt().toEpochMilli())
                        .build());
            }
            cursor = batch.getLast().attachmentId();
        }
        observer.onCompleted();
    }

    private static PageContent toProto(Page p, Space s) {
        return PageContent.newBuilder()
                .setPageId(p.getId())
                .setSpaceId(p.getSpaceId())
                .setSpaceKey(s.getKey())
                .setSpaceName(s.getName())
                .setParentId(p.getParentId() == null ? 0L : p.getParentId())
                .setType(switch (p.getType()) {
                    case PAGE -> com.platform.proto.wiki.v1.PageType.PAGE;
                    case FOLDER -> com.platform.proto.wiki.v1.PageType.FOLDER;
                })
                .setStatus(switch (p.getStatus()) {
                    case DRAFT -> com.platform.proto.wiki.v1.PageStatus.DRAFT;
                    case PUBLISHED -> com.platform.proto.wiki.v1.PageStatus.PUBLISHED;
                })
                .setTitle(p.getTitle())
                .setContent(p.getContent())
                .setVersion(p.getVersion())
                .setAuthorId(p.getUpdatedBy())
                .setUpdatedAt(p.getUpdatedAt().toEpochMilli())
                .build();
    }
}
