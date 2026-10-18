package com.platform.wikibackend.space;

import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.event.EventRelay;
import com.platform.wikibackend.event.WikiEvents;
import com.platform.wikibackend.permission.AccessScope;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.permission.PermissionDecision;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
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
    private final com.platform.wikibackend.page.PageDataPurger purger;
    private final com.platform.wikibackend.audit.AuditService audit;
    /*
     * 스페이스 구독(V32)은 PG에서 FK cascade로 함께 사라지지만, H2 테스트 환경엔 FK가 없어
     * 고아 행이 남는다. 같은 id가 재사용되면 예전 구독자에게 남의 스페이스 알림이 가므로
     * 리포지토리를 직접 들고 지운다(SpaceWatchService를 주입하면 그쪽이 SpaceService를 다시
     * 부르고 있어 순환이 된다).
     */
    private final com.platform.wikibackend.repository.SpaceWatchRepository spaceWatches;
    private final com.platform.wikibackend.repository.SpaceLabelRepository spaceLabels;

    /** 스페이스당 라벨 상한 — 분류 칩이라 많으면 디렉토리 한 줄이 읽히지 않는다. */
    public static final int MAX_LABELS_PER_SPACE = 10;

    @Transactional(readOnly = true)
    public List<SpaceResponse> listAccessible(long userId) {
        AccessScope scope = permissions.accessibleSpaces(userId);
        List<Space> visible = spaces.findAll().stream()
                .filter(s -> scope.contains(s.getId()))
                .toList();
        if (visible.isEmpty()) return List.of();
        // 라벨은 목록 전체에 한 번 — 스페이스마다 묻지 않는다.
        java.util.Map<Long, List<String>> labelsOf = new java.util.HashMap<>();
        for (com.platform.wikibackend.domain.SpaceLabel label
                : spaceLabels.findBySpaceIdInOrderByName(visible.stream().map(Space::getId).toList())) {
            labelsOf.computeIfAbsent(label.getSpaceId(), k -> new java.util.ArrayList<>()).add(label.getName());
        }
        labelsOf.values().forEach(java.util.Collections::sort); // 코드유닛 순 — DB collation에 기대지 않는다
        return visible.stream()
                .map(s -> SpaceResponse.from(s, labelsOf.getOrDefault(s.getId(), List.of())))
                .toList();
    }

    /** 단건 응답 — VIEW 가드 + 라벨. */
    @Transactional(readOnly = true)
    public SpaceResponse get(long userId, long spaceId) {
        return response(getForView(userId, spaceId));
    }

    private SpaceResponse response(Space s) {
        return SpaceResponse.from(s, labelsOf(s.getId()));
    }

    /**
     * 이름순은 **코드유닛**(Java 정렬)이다. SQL `order by name`은 DB collation을 따라 하이픈·한글의
     * 순서가 설치마다 달라질 수 있다 — 응답 순서와 교체 시의 비교가 그것에 흔들리면 안 된다.
     */
    private List<String> labelsOf(long spaceId) {
        return spaceLabels.findBySpaceIdOrderByName(spaceId).stream()
                .map(com.platform.wikibackend.domain.SpaceLabel::getName)
                .sorted()
                .toList();
    }

    /**
     * 스페이스 라벨 전량 교체 — 스페이스 ADMIN만(이름·설명 수정과 같은 권한). 문서 라벨과 같은
     * 정규화 규칙이고, 중복은 하나로 접는다. 바뀐 게 없으면 감사 기록을 남기지 않는다.
     */
    public SpaceResponse setLabels(long userId, long spaceId, List<String> raw) {
        if (!spaces.existsById(spaceId)) throw new NotFoundException("스페이스 없음: " + spaceId);
        require(userId, spaceId, WikiAction.ADMIN);
        // 스페이스 행 잠금 — 두 관리자의 동시 교체가 각자 "빈 상태"를 보고 지운 뒤 둘 다 넣으면 합집합이
        // 남아 상한을 넘는다. 잠그면 뒤 요청은 앞 요청이 커밋한 상태에서 교체한다.
        Space s = spaces.findByIdForUpdate(spaceId)
                .orElseThrow(() -> new NotFoundException("스페이스 없음: " + spaceId));
        java.util.Set<String> normalized = new java.util.TreeSet<>(); // 이름순(코드유닛) = 조회 순서
        for (String value : raw) normalized.add(com.platform.wikibackend.domain.PageLabel.normalize(value));
        if (normalized.size() > MAX_LABELS_PER_SPACE) {
            throw new IllegalArgumentException("스페이스 라벨은 " + MAX_LABELS_PER_SPACE + "개까지입니다");
        }
        List<String> before = labelsOf(spaceId);
        if (before.equals(List.copyOf(normalized))) return SpaceResponse.from(s, before);
        spaceLabels.deleteBySpaceId(spaceId);
        spaceLabels.saveAll(normalized.stream()
                .map(name -> com.platform.wikibackend.domain.SpaceLabel.of(spaceId, name, userId))
                .toList());
        audit.record(spaceId, userId, com.platform.wikibackend.domain.AuditAction.SPACE_UPDATED,
                "SPACE", spaceId, s.getName(),
                "라벨 변경: " + (normalized.isEmpty() ? "(없음)" : String.join(", ", normalized)));
        return SpaceResponse.from(s, List.copyOf(normalized));
    }

    public SpaceResponse create(long userId, SpaceCreateRequest req) {
        if (spaces.existsByKey(req.key())) throw new IllegalArgumentException("이미 존재하는 key: " + req.key());
        Space saved = spaces.save(Space.of(req.key(), req.name(), req.description(), userId));
        // 생성자 자동 ADMIN — 실패해도 스페이스 생성은 유효(관리자가 grants REST로 수습 가능), 로그는 클라이언트가 남김
        permissions.grantSpaceAdmin(userId, saved.getId());
        events.afterCommit(WikiEvents.spaceCreated(userId, saved));
        return SpaceResponse.from(saved, List.of());
    }

    /**
     * 개인 스페이스(W23) — 없으면 만들고 있으면 그대로 준다(멱등).
     *
     * 만든 사람이 곧 주인이라 ADMIN을 부여한다. grant가 실패하면 스페이스는 생겼는데 주인이 못
     * 들어가는 상태가 되므로 일반 생성과 달리 **되돌린다** — 개인 스페이스는 "내 것"이 전제다.
     */
    public SpaceResponse ensurePersonal(long userId, String userName) {
        Space existing = spaces.findByOwnerId(userId).orElse(null);
        if (existing != null) return response(existing);
        String name = userName == null || userName.isBlank() ? "사용자 " + userId : userName.trim();
        Space saved = spaces.save(Space.personalOf(userId, name));
        if (!permissions.grantSpaceAdmin(userId, saved.getId())
                && !permissions.isAllowed(userId, saved.getId(), WikiAction.ADMIN)) {
            throw new com.platform.common.error.ServiceUnavailableException(
                    "권한 서비스에 연결할 수 없어 개인 스페이스를 만들지 못했습니다");
        }
        events.afterCommit(WikiEvents.spaceCreated(userId, saved));
        return SpaceResponse.from(saved, List.of());
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
        String before = s.getName();
        s.update(req.name(), req.description());
        audit.record(spaceId, userId, com.platform.wikibackend.domain.AuditAction.SPACE_UPDATED,
                "SPACE", spaceId, s.getName(),
                before.equals(s.getName()) ? null : "이름 변경: " + before);
        events.afterCommit(WikiEvents.spaceUpdated(userId, s));
        return response(s);
    }

    public void delete(long userId, long spaceId) {
        Space doomed = spaces.findById(spaceId).orElseThrow(() -> new NotFoundException("스페이스 없음: " + spaceId));
        require(userId, spaceId, WikiAction.ADMIN);

        // 스페이스 전체 정리. 휴지통(V13) 행까지 포함해야 버려진 문서의 첨부 객체·딸린 행이 고아로 남지 않는다.
        // id만 읽는다(본문 미적재) — 딸린 데이터는 테이블당 bulk DELETE(PageDataPurger, 감사 W-05·W-15).
        List<Long> pageIds = pages.findAnyIdsBySpaceId(spaceId);
        purger.purgeDependents(pageIds);
        // 한 문장 bulk DELETE(휴지통 포함, 네이티브) — 부모·자식 cascade와 충돌하지 않고, 스페이스 크기만큼
        // OR 조건을 늘어놓지 않는다. JPQL 대량 삭제는 @SQLRestriction 때문에 휴지통 행을 놓친다.
        pages.deleteAllBySpaceIdIncludingTrashed(spaceId);
        spaceWatches.deleteBySpaceId(spaceId); // 구독 원장도 스페이스와 함께 사라진다(V32)
        spaceLabels.deleteBySpaceId(spaceId); // 라벨도(V41) — PG는 FK cascade, H2에는 FK가 없다

        // 삭제 기록은 스페이스보다 오래 남는다(V30) — 전역 관리자의 "스페이스 삭제 기록"이 읽는다.
        // 같은 트랜잭션이라 삭제가 롤백되면 기록도 함께 사라진다.
        audit.record(spaceId, userId, com.platform.wikibackend.domain.AuditAction.SPACE_DELETED, "SPACE",
                spaceId, doomed.getName() + " (" + doomed.getKey() + ")", "문서 " + pageIds.size() + "건 함께 삭제");
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
        PermissionDecision decision = permissions.check(userId, spaceId, action);
        if (decision.allowed()) return;
        // 계정 상태로 막힌 것이면 그 사실을 그대로 말한다 — "EDIT 권한이 필요합니다"는 승인 대기 중인
        // 사람에게 엉뚱한 조치(스페이스 관리자에게 권한 요청)를 시킨다. 문구는 org 상태와 한 곳에서
        // 정한다(PermissionDecision.accountMessage).
        String message = decision.accountMessage();
        throw new ForbiddenException(message == null
                ? action + " 권한이 필요합니다 (space " + spaceId + ")"
                : message);
    }

    /**
     * 예외 대신 판정값 — 여러 스페이스에 걸친 목록을 거를 때 쓴다(검색 결과 경로 등).
     * 하나가 막혔다고 요청 전체를 403으로 끝내면 볼 수 있는 나머지까지 잃는다.
     */
    public boolean canView(long userId, long spaceId) {
        return permissions.isAllowed(userId, spaceId, WikiAction.VIEW);
    }

    /** 전역 권한(GLOBAL grant) 보유자에게 "전부"를 구체적인 id 집합으로 풀어 준다. */
    @Transactional(readOnly = true)
    public java.util.Set<Long> allIds() {
        return spaces.findAll().stream()
                .map(com.platform.wikibackend.domain.Space::getId)
                .collect(java.util.stream.Collectors.toSet());
    }
}
