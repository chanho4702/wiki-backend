package com.platform.wikibackend.audit;

import com.platform.wikibackend.domain.AuditAction;
import com.platform.wikibackend.domain.AuditLog;
import com.platform.wikibackend.domain.Page;
import com.platform.common.error.ForbiddenException;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    private final AuditLogRepository logs;
    /*
     * SpaceService가 아니라 PermissionClient를 직접 쓴다.
     *
     * 기록을 남기는 쪽(SpaceService·PageService·AttachmentService…)이 전부 이 서비스를 부르므로,
     * 여기서 그중 하나를 되부르면 순환이 된다(실제로 SpaceService에서 그렇게 터졌다). 감사
     * 서비스는 기록기이지 상위 서비스가 아니다 — 판정에 필요한 최소한만 들고 있는다.
     */
    private final PermissionClient permissions;

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
        if (!permissions.isAllowed(userId, spaceId, WikiAction.ADMIN)) {
            throw new ForbiddenException("감사 로그는 스페이스 관리자만 볼 수 있습니다");
        }
        return logs.findBySpace(spaceId, Limit.of(PAGE_SIZE)).stream()
                .map(AuditEntry::from)
                .toList();
    }

    /**
     * 스페이스 삭제 기록 — 전역 관리자(GLOBAL grant)만. 스페이스가 없으니 스페이스 ADMIN으로는
     * 판정할 수 없고, 지워진 스페이스의 이름은 그 조직을 관리하는 사람만 볼 일이다.
     */
    @Transactional(readOnly = true)
    public List<AuditEntry> listSpaceDeletions(long userId) {
        if (!permissions.accessibleSpaces(userId).all()) {
            throw new ForbiddenException("스페이스 삭제 기록은 전역 관리자만 볼 수 있습니다");
        }
        return logs.findByAction(AuditAction.SPACE_DELETED.name(), Limit.of(PAGE_SIZE)).stream()
                .map(AuditEntry::from)
                .toList();
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
