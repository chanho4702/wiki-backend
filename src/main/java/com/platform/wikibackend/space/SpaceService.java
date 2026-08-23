package com.platform.wikibackend.space;

import com.platform.wikibackend.attachment.AttachmentStorageRouter;
import com.platform.wikibackend.common.ForbiddenException;
import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.permission.AccessScope;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import com.platform.wikibackend.space.dto.SpaceCreateRequest;
import com.platform.wikibackend.space.dto.SpaceResponse;
import com.platform.wikibackend.space.dto.SpaceUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SpaceService {

    private final SpaceRepository spaces;
    private final PermissionClient permissions;
    private final EventRelay events;
    private final PageRepository pages;
    private final PageCommentRepository comments;
    private final PageRevisionRepository revisions;
    private final AttachmentRepository attachments;
    private final AttachmentStorageRouter storage;

    @Transactional(readOnly = true)
    public List<SpaceResponse> listAccessible(long userId) {
        AccessScope scope = permissions.accessibleSpaces(userId);
        return spaces.findAll().stream()
                .filter(s -> scope.contains(s.getId()))
                .map(SpaceResponse::from)
                .toList();
    }

    public SpaceResponse create(long userId, SpaceCreateRequest req) {
        if (spaces.existsByKey(req.key())) throw new IllegalArgumentException("이미 존재하는 key: " + req.key());
        Space saved = spaces.save(Space.of(req.key(), req.name(), req.description(), userId));
        // 생성자 자동 ADMIN — 실패해도 스페이스 생성은 유효(관리자가 grants REST로 수습 가능), 로그는 클라이언트가 남김
        permissions.grantSpaceAdmin(userId, saved.getId());
        events.afterCommit(WikiEvents.spaceCreated(userId, saved));
        return SpaceResponse.from(saved);
    }

    /** VIEW 가드 포함 단건 조회 — 페이지·첨부 서비스가 재사용. */
    @Transactional(readOnly = true)
    public Space getForView(long userId, long spaceId) {
        Space s = spaces.findById(spaceId).orElseThrow(() -> new NotFoundException("스페이스 없음: " + spaceId));
        require(userId, spaceId, WikiAction.VIEW);
        return s;
    }

    public SpaceResponse update(long userId, long spaceId, SpaceUpdateRequest req) {
        Space s = spaces.findById(spaceId).orElseThrow(() -> new NotFoundException("스페이스 없음: " + spaceId));
        require(userId, spaceId, WikiAction.ADMIN);
        s.update(req.name(), req.description());
        events.afterCommit(WikiEvents.spaceUpdated(userId, s));
        return SpaceResponse.from(s);
    }

    public void delete(long userId, long spaceId) {
        if (!spaces.existsById(spaceId)) throw new NotFoundException("스페이스 없음: " + spaceId);
        require(userId, spaceId, WikiAction.ADMIN);

        // 스페이스 전체 정리 — 디스크 파일은 DB cascade가 못 지우므로 코드로. (H2 테스트 환경엔 FK도 없음)
        List<Page> all = pages.findBySpaceIdOrderById(spaceId);
        for (Page p : all) {
            attachments.findByPageId(p.getId()).forEach(a -> {
                storage.deleteAfterCommit(a.getStorageBackend(), a.getStorageBucket(),
                        a.getStorageKey(), a.getStorageVersion());
            });
            attachments.deleteByPageId(p.getId());
            revisions.deleteByPageId(p.getId());
            comments.deleteByPageId(p.getId());
        }
        // 개별 DELETE(deleteAll)는 운영 PG에서 부모 페이지 삭제가 자식을 cascade로 먼저 지운 뒤
        // 이어지는 자식 개별 DELETE가 0행 → Hibernate StaleStateException(500)을 낸다.
        // 단일 bulk DELETE(deleteAllInBatch)는 row count를 기대하지 않아 cascade와 충돌하지 않는다.
        pages.deleteAllInBatch(all);

        spaces.deleteById(spaceId);
        // 스페이스가 사라져도 org의 grant는 남는다 — 같은 id가 재사용되면 예전 멤버에게
        // 권한이 되살아나므로 함께 회수한다(v0.3.0 RevokeGrant). 실패해도 삭제는 되돌리지 않는다.
        permissions.revokeSpaceGrants(spaceId);
        events.afterCommit(WikiEvents.spaceDeleted(userId, spaceId));
    }

    /** 다른 서비스(페이지·첨부)도 쓰는 공용 가드. */
    /** 트리 이동/재정렬 직렬화용 스페이스 행 잠금 — 순번 발급이 겹치지 않게 한다(V9). */
    public void lockForReorder(long spaceId) {
        spaces.findByIdForUpdate(spaceId)
                .orElseThrow(() -> new NotFoundException("스페이스 없음: " + spaceId));
    }

    public void require(long userId, long spaceId, WikiAction action) {
        if (!permissions.isAllowed(userId, spaceId, action)) {
            throw new ForbiddenException(action + " 권한이 필요합니다 (space " + spaceId + ")");
        }
    }
}
