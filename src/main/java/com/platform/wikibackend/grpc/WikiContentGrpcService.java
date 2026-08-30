package com.platform.wikibackend.grpc;

import com.platform.proto.wiki.v1.AttachmentMeta;
import com.platform.proto.wiki.v1.GetAttachmentMetaRequest;
import com.platform.proto.wiki.v1.GetPageContentRequest;
import com.platform.proto.wiki.v1.ListAttachmentsRequest;
import com.platform.proto.wiki.v1.ListPageContentsRequest;
import com.platform.proto.wiki.v1.PageContent;
import com.platform.proto.wiki.v1.FilterVisiblePagesRequest;
import com.platform.proto.wiki.v1.FilterVisiblePagesResponse;
import com.platform.proto.wiki.v1.WikiContentServiceGrpc;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.repository.AttachmentIndexRow;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageLabelRepository;
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
    private final PageLabelRepository labelRepository;
    private final com.platform.wikibackend.permission.EffectivePermissionService effective;
    private final com.platform.wikibackend.permission.PermissionClient permissions;

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
            // NOT_FOUND가 아니다. 색인기는 NOT_FOUND를 "이미 지워졌다"로 읽고 색인에서 빼는데,
            // 이건 페이지가 살아 있는데 스페이스만 사라진 **데이터 불일치**다. 삭제로 처리하면
            // 스페이스가 복구돼도 새 이벤트가 오기 전까지 색인이 돌아오지 않는다.
            log.error("고아 페이지 — 스페이스가 없다: page={} space={}",
                    request.getPageId(), page.get().getSpaceId());
            observer.onError(Status.FAILED_PRECONDITION
                    .withDescription("space missing for page: " + request.getPageId())
                    .asRuntimeException());
            return;
        }
        observer.onNext(toProto(page.get(), space.get(), labelsOf(page.get().getId())));
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
            // 배치 단위로 라벨을 한 번에 읽는다 — 페이지마다 되물으면 백필이 N+1이 된다.
            Map<Long, List<String>> labelsByPage = labelsOf(batch.stream().map(Page::getId).toList());
            for (Page p : batch) {
                Space s = spaces.computeIfAbsent(p.getSpaceId(),
                        id -> spaceRepository.findById(id).orElse(null));
                if (s == null) {
                    // 스페이스가 사라진 고아 페이지 — 색인할 표시명이 없으니 건너뛴다(로그로 남긴다)
                    log.warn("백필 중 고아 페이지 건너뜀: page={} space={}", p.getId(), p.getSpaceId());
                    continue;
                }
                observer.onNext(toProto(p, s, labelsByPage.getOrDefault(p.getId(), List.of())));
            }
            cursor = batch.getLast().getId();
        }
        observer.onCompleted();
    }

    @Override
    @Transactional(readOnly = true)
    public void getAttachmentMeta(GetAttachmentMetaRequest request, StreamObserver<AttachmentMeta> observer) {
        Optional<AttachmentIndexRow> row = attachmentRepository.findForIndexingById(request.getAttachmentId());
        if (row.isEmpty()) {
            // 페이지와 같은 계약: 없으면 삭제로 간주해 색인에서 뺀다.
            // (조인 대상인 페이지·스페이스가 사라진 경우도 여기로 온다 — 첨부는 상위가 사라지면
            //  함께 정리되는 게 정상이라 페이지처럼 FAILED_PRECONDITION으로 나누지 않는다.)
            observer.onError(Status.NOT_FOUND
                    .withDescription("attachment not found: " + request.getAttachmentId())
                    .asRuntimeException());
            return;
        }
        observer.onNext(toProto(row.get()));
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
                observer.onNext(toProto(a));
            }
            cursor = batch.getLast().attachmentId();
        }
        observer.onCompleted();
    }

    private static AttachmentMeta toProto(AttachmentIndexRow a) {
        return AttachmentMeta.newBuilder()
                .setAttachmentId(a.attachmentId())
                .setPageId(a.pageId())
                .setSpaceId(a.spaceId())
                .setSpaceKey(a.spaceKey())
                .setSpaceName(a.spaceName())
                .setFilename(a.filename())
                .setContentType(a.contentType())
                .setSizeBytes(a.sizeBytes())
                .setUploadedBy(a.uploadedBy())
                .setCreatedAt(a.createdAt().toEpochMilli())
                .build();
    }

    /** 한 페이지의 라벨 — 단건 조달 경로용. */
    private List<String> labelsOf(long pageId) {
        return labelRepository.findByPageIdOrderByName(pageId).stream()
                .map(com.platform.wikibackend.domain.PageLabel::getName)
                .toList();
    }

    /** 여러 페이지의 라벨을 한 번에 — 백필의 N+1을 막는다. */
    private Map<Long, List<String>> labelsOf(java.util.Collection<Long> pageIds) {
        Map<Long, List<String>> byPage = new HashMap<>();
        for (var label : labelRepository.findByPageIdIn(pageIds)) {
            byPage.computeIfAbsent(label.getPageId(), k -> new java.util.ArrayList<>()).add(label.getName());
        }
        byPage.values().forEach(java.util.Collections::sort);
        return byPage;
    }

    private static PageContent toProto(Page p, Space s, List<String> labels) {
        return PageContent.newBuilder()
                .setPageId(p.getId())
                .setSpaceId(p.getSpaceId())
                .setSpaceKey(s.getKey())
                .setSpaceName(s.getName())
                .setParentId(p.getParentId() == null ? 0L : p.getParentId())
                .setType(switch (p.getType()) {
                    case PAGE -> com.platform.proto.wiki.v1.PageType.PAGE;
                    case FOLDER -> com.platform.proto.wiki.v1.PageType.FOLDER;
                    // proto에는 BLOG가 없다(0.11.0). 색인기에는 페이지로 보인다 — 검색 결과의 타입 표시만 다르다.
                    case BLOG -> com.platform.proto.wiki.v1.PageType.PAGE;
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
                .addAllLabels(labels)
                .build();
    }

    /**
     * 검색 결과 권한 후필터(W18) — 이 rpc만은 사용자 기준 판정이다(user_id를 받는다).
     * space VIEW(org) + 페이지 제한(effective)을 모두 통과한 페이지만 남긴다.
     * 스페이스 단위로 묶어 제한 인덱스 로드를 공유한다(스페이스당 2쿼리).
     */
    @Override
    @Transactional(readOnly = true)
    public void filterVisiblePages(FilterVisiblePagesRequest req, StreamObserver<FilterVisiblePagesResponse> out) {
        try {
            long userId = req.getUserId();
            List<Page> found = pageRepository.findAllById(req.getPageIdsList());
            Map<Long, List<Page>> bySpace = new HashMap<>();
            for (Page p : found) bySpace.computeIfAbsent(p.getSpaceId(), k -> new java.util.ArrayList<>()).add(p);

            FilterVisiblePagesResponse.Builder res = FilterVisiblePagesResponse.newBuilder();
            for (Map.Entry<Long, List<Page>> e : bySpace.entrySet()) {
                if (!permissions.isAllowed(userId, e.getKey(), com.platform.wikibackend.permission.WikiAction.VIEW)) {
                    continue; // 스페이스 자체가 안 보이면 전부 제외(fail-closed)
                }
                java.util.Set<Long> visible = effective.visiblePageIds(userId, e.getKey());
                for (Page p : e.getValue()) {
                    if (visible == null || visible.contains(p.getId())) res.addVisiblePageIds(p.getId());
                }
            }
            out.onNext(res.build());
            out.onCompleted();
        } catch (Exception e) {
            log.error("visible 필터 실패 — 검색 누출 방지를 위해 오류로 닫는다: user={}", req.getUserId(), e);
            out.onError(Status.INTERNAL.withDescription("visible 필터 실패").asRuntimeException());
        }
    }
}
