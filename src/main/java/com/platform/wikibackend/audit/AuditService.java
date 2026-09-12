package com.platform.wikibackend.audit;

import com.platform.wikibackend.domain.AuditAction;
import com.platform.wikibackend.domain.AuditLog;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.common.error.ForbiddenException;
import com.platform.wikibackend.permission.GlobalAdminGuard;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AuditLogRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 감사 로그(W23) — 되돌리기 어려운 조작의 흔적.
 *
 * "누가 이 문서를 지웠나", "언제부터 이 페이지가 잠겼나"를 확인할 방법이 없었다. 이력이 남는
 * 것은 본문 리비전뿐이고, 지우기·권한 변경처럼 되돌리기 어려운 조작은 흔적이 없었다.
 *
 * 기록은 **호출한 조작과 같은 트랜잭션에서** 남긴다. 조작이 롤백되면 기록도 함께 사라져야
 * 한다 — 일어나지 않은 일이 로그에 남으면 로그를 믿을 수 없게 된다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    /** 한 번에 돌려주는 기록 수. 목록으로 훑는 화면이라 더 길면 아무도 끝까지 보지 않는다. */
    public static final int PAGE_SIZE = 100;

    /** 전역 피드의 기본 페이지 크기 — 대시보드 "최근 활동" 카드가 한 화면에 담는 양. */
    public static final int FEED_DEFAULT_SIZE = 20;
    /**
     * 전역 피드의 페이지 상한. 넘겨 오면 거절하지 않고 잘라서 준다 — 응답의 {@code size}가
     * 실제 적용값이라 호출자가 무엇이 적용됐는지 알 수 있다.
     */
    public static final int FEED_MAX_SIZE = 100;

    /** 최신이 먼저. 같은 시각의 기록도 흔들리지 않게 id를 2차 기준으로 둔다(스페이스 조회와 같은 순서). */
    private static final Sort FEED_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /** 응답 문서에 나열하는 eventType 값 집합. {@link AuditAction}과 같은 순서다. */
    static final String EVENT_TYPES = """
            조작 종류. PAGE_TRASHED(페이지 휴지통 이동), PAGE_RESTORED(페이지 복원), \
            PAGE_PURGED(페이지 완전 삭제), PAGE_ARCHIVED(페이지 보관), PAGE_UNARCHIVED(페이지 보관 해제), \
            PAGE_RESTRICTIONS_CHANGED(페이지 제한 변경), PAGE_OWNER_CHANGED(페이지 소유자 변경), \
            PAGE_VERIFIED(페이지 검증), PAGE_UNVERIFIED(페이지 검증 해제), ATTACHMENT_DELETED(첨부 삭제), \
            SPACE_UPDATED(스페이스 정보 변경), SPACE_DELETED(스페이스 삭제), TEMPLATE_CREATED(템플릿 생성), \
            TEMPLATE_UPDATED(템플릿 수정), TEMPLATE_DELETED(템플릿 삭제), IMPORTED(외부 위키에서 이관) \
            중 하나. 본문 수정은 리비전이 남기므로 감사 로그에 없다.""";


    private final AuditLogRepository logs;
    /**
     * 전역 피드가 spaceKey를 채우는 데만 쓴다. SpaceService가 아니라 리포지토리를 직접 잡는
     * 이유는 아래 PermissionClient와 같다 — 상위 서비스를 되부르면 순환이 된다.
     */
    private final SpaceRepository spaces;
    /*
     * SpaceService가 아니라 PermissionClient를 직접 쓴다.
     *
     * 기록을 남기는 쪽(SpaceService·PageService·AttachmentService…)이 전부 이 서비스를 부르므로,
     * 여기서 그중 하나를 되부르면 순환이 된다(실제로 SpaceService에서 그렇게 터졌다). 감사
     * 서비스는 기록기이지 상위 서비스가 아니다 — 판정에 필요한 최소한만 들고 있는다.
     */
    private final PermissionClient permissions;
    /** 전역 관리자 판정(GLOBAL·ADMIN) — 스페이스 감사와 달리 스페이스가 없는 경로가 쓴다. */
    private final GlobalAdminGuard globalAdmin;

    public void record(long spaceId, long actorId, AuditAction action, String targetType,
                       Long targetId, String targetLabel, String detail) {
        logs.save(AuditLog.of(spaceId, actorId, action, targetType, targetId, targetLabel, detail));
    }

    /** 페이지 대상 기록의 흔한 형태 — 제목을 라벨로 쓴다. */
    public void recordPage(long actorId, AuditAction action, Page page, String detail) {
        record(page.getSpaceId(), actorId, action, "PAGE", page.getId(), page.getTitle(), detail);
    }

    /**
     * 조회는 스페이스 **ADMIN**만. 누가 무엇을 지웠는지는 그 스페이스를 볼 수 있는 모두가
     * 알아야 할 정보가 아니다 — 제한된 문서의 제목이 기록에 남아 있기 때문이기도 하다.
     */
    @Transactional(readOnly = true)
    public List<AuditEntry> list(long userId, long spaceId) {
        com.platform.wikibackend.permission.PermissionDecision decision =
                permissions.check(userId, spaceId, WikiAction.ADMIN);
        if (!decision.allowed()) {
            String message = decision.accountMessage();
            throw new ForbiddenException(message == null ? "감사 로그는 스페이스 관리자만 볼 수 있습니다" : message);
        }
        return logs.findBySpace(spaceId, Limit.of(PAGE_SIZE)).stream()
                .map(AuditEntry::from)
                .toList();
    }

    /**
     * 스페이스 삭제 기록 — 전역 관리자만. 스페이스가 없으니 스페이스 ADMIN으로는 판정할 수 없고,
     * 지워진 스페이스의 이름은 그 조직을 관리하는 사람만 볼 일이다. 판정은 org
     * {@code CheckPermission(GLOBAL, ADMIN)}이다({@link GlobalAdminGuard}) — grant 목록을 훑던
     * 옛 방식과 달리 승인 대기·정지된 계정에게는 그 사실을 403 문구로 말한다.
     */
    @Transactional(readOnly = true)
    public List<AuditEntry> listSpaceDeletions(long userId) {
        globalAdmin.require(userId, "스페이스 삭제 기록은 전역 관리자만 볼 수 있습니다");
        return logs.findByAction(AuditAction.SPACE_DELETED.name(), Limit.of(PAGE_SIZE)).stream()
                .map(AuditEntry::from)
                .toList();
    }

    /**
     * 전역 감사 피드(관리자 대시보드 "최근 활동") — 전역 관리자만.
     *
     * 판정 기준은 스페이스 삭제 기록·관리자 현황과 같다 — org {@code CheckPermission(GLOBAL, ADMIN)}
     * ({@link GlobalAdminGuard}). 전 스페이스를 가로지르는 목록은 스페이스 하나의 ADMIN이 볼 것이
     * 아니다 — 제한된 문서의 제목이 그대로 라벨에 들어 있다. org-service가 불능이면 판정이 던지는
     * {@code ServiceUnavailableException}이 503으로 나간다(fail-closed — 권한 없음으로 오인하지 않는다).
     *
     * 스페이스 스코프 조회와 달리 페이지네이션이 있다. 전역 목록은 스페이스 수만큼 빨리 자라
     * 상위 100건 고정으로는 어제 일도 못 본다.
     */
    @Transactional(readOnly = true)
    public AuditFeedPage feed(long userId, Integer page, Integer size, String type, Instant since) {
        globalAdmin.require(userId, "전역 감사 피드는 전역 관리자만 볼 수 있습니다");
        int pageNumber = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size <= 0 ? FEED_DEFAULT_SIZE : Math.min(size, FEED_MAX_SIZE);
        String action = action(type);
        // since가 없으면 EPOCH — created_at은 not null이라 전건이 걸린다(질의를 하나로 유지한다).
        Instant from = since == null ? Instant.EPOCH : since;
        Pageable pageable = PageRequest.of(pageNumber, pageSize, FEED_SORT);

        org.springframework.data.domain.Page<AuditLog> rows = action == null
                ? logs.findByCreatedAtGreaterThanEqual(from, pageable)
                : logs.findByActionAndCreatedAtGreaterThanEqual(action, from, pageable);

        Map<Long, String> keys = spaceKeys(rows.getContent());
        List<AuditFeedItem> items = rows.getContent().stream()
                .map(log -> AuditFeedItem.from(log, keys.get(log.getSpaceId())))
                .toList();
        return new AuditFeedPage(items, pageNumber, pageSize, rows.getTotalElements());
    }

    /**
     * 유형 필터를 enum 이름으로 정규화한다. 모르는 값은 빈 목록이 아니라 400 — 오타를 조용히
     * "기록 없음"으로 돌려주면 화면에서 구별할 방법이 없다.
     */
    private static String action(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        for (AuditAction value : AuditAction.values()) {
            if (value.name().equals(normalized)) {
                return normalized;
            }
        }
        throw new IllegalArgumentException("알 수 없는 감사 유형입니다: " + type);
    }

    /**
     * 이 페이지에 실린 스페이스의 key를 한 번에 읽는다(행마다 조회하면 N+1).
     * 지워진 스페이스는 빠진다 — 기록은 스페이스보다 오래 살고(V30), 그 자리는 null이다.
     */
    private Map<Long, String> spaceKeys(List<AuditLog> rows) {
        Set<Long> ids = rows.stream().map(AuditLog::getSpaceId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return spaces.findAllById(ids).stream()
                .collect(Collectors.toMap(Space::getId,
                        Space::getKey, (a, b) -> a));
    }

    /** 전역 피드 한 건. 필드 이름은 프론트 어댑터와 맞춘 계약이다(엔티티 컬럼명과 다르다). */
    @Schema(description = "전역 감사 피드 항목.")
    public record AuditFeedItem(
            @Schema(description = "감사 기록 ID", example = "123") Long id,
            @Schema(description = EVENT_TYPES, example = "PAGE_TRASHED") String eventType,
            @Schema(description = "조작한 사용자 ID", example = "7") Long actorId,
            @Schema(description = "대상 스페이스 ID", example = "3") Long spaceId,
            @Schema(description = "대상 스페이스 key. 이미 지워진 스페이스면 null", example = "DOCS")
            String spaceKey,
            @Schema(description = "대상 페이지 ID. 페이지 대상 기록이 아니면 null", example = "55") Long pageId,
            @Schema(description = "대상의 그때 이름(페이지 제목·첨부 파일명·스페이스 이름 등)",
                    example = "설계 문서") String targetTitle,
            @Schema(description = "한 줄 요약 — 조작 라벨에 상세가 있으면 덧붙인다", example = "페이지 휴지통 이동")
            String summary,
            @Schema(description = "발생 시각(ISO-8601 UTC)", example = "2026-09-12T01:02:03Z")
            String occurredAt) {

        public static AuditFeedItem from(AuditLog log, String spaceKey) {
            String label = AuditAction.labelOf(log.getAction());
            return new AuditFeedItem(
                    log.getId(),
                    log.getAction(),
                    log.getActorId(),
                    log.getSpaceId(),
                    spaceKey,
                    "PAGE".equals(log.getTargetType()) ? log.getTargetId() : null,
                    log.getTargetLabel(),
                    log.getDetail() == null ? label : label + " — " + log.getDetail(),
                    log.getCreatedAt() == null ? null : log.getCreatedAt().toString());
        }
    }

    /** 전역 피드 한 페이지. */
    @Schema(description = "전역 감사 피드 한 페이지.")
    public record AuditFeedPage(
            @Schema(description = "이 페이지의 기록. 최신이 먼저") List<AuditFeedItem> items,
            @Schema(description = "0부터 세는 페이지 번호", example = "0") int page,
            @Schema(description = "실제 적용된 페이지 크기(상한 100)", example = "20") int size,
            @Schema(description = "필터를 적용한 전체 건수", example = "1234") long total) {
    }

    public record AuditEntry(
            Long id,
            String action,
            String targetType,
            Long targetId,
            String targetLabel,
            String detail,
            Long actorId,
            String createdAt) {

        public static AuditEntry from(AuditLog log) {
            return new AuditEntry(log.getId(), log.getAction(), log.getTargetType(),
                    log.getTargetId(), log.getTargetLabel(), log.getDetail(), log.getActorId(),
                    log.getCreatedAt() == null ? null : log.getCreatedAt().toString());
        }
    }
}
