package com.platform.wikibackend.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.platform.wikibackend.attachment.AttachmentLifecycleStatus;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.permission.GlobalAdminGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * 관리자 대시보드가 읽는 위키 현황 집계(설계 §4.1).
 *
 * 두 가지 규율이 있다.
 *
 * <p><b>전역 관리자만.</b> 판정은 {@link GlobalAdminGuard} — org {@code CheckPermission(GLOBAL, ADMIN)}
 * 하나다. 스페이스 삭제 기록(`/api/wiki/audit/space-deletions`)과 같은 기준을 쓴다: 전 스페이스를
 * 가로지르는 숫자는 스페이스 하나의 ADMIN이 볼 것이 아니다. 막힌 이유가 계정 상태면(승인 대기·정지)
 * 403 문구가 그 사실을 말하고, org-service가 불능이면 {@code ServiceUnavailableException}이 그대로
 * 503으로 나간다(fail-closed — 판정을 못 하면 열지 않는다).
 *
 * <p><b>60초 캐시.</b> 화면이 몇 개 떠 있든 DB가 받는 집계는 60초에 한 번을 넘지 않는다.
 * 캐시는 사용자별이 아니다 — 결과가 전역 숫자라 누가 물어도 같고, 권한 판정은 캐시 앞에서
 * 매번 돈다(권한을 잃은 사용자가 캐시된 값을 받지 않는다).
 */
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    /** 설계 §0의 "통계 60초 캐시". */
    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    /** "최근 편집"의 창. 7일은 대시보드가 활동을 판단하는 단위다(설계 §4.1). */
    private static final Duration EDIT_WINDOW = Duration.ofDays(7);
    /** 전역 집계라 키가 하나뿐이다 — Caffeine을 단일 슬롯 메모이저로 쓴다. */
    private static final String KEY = "wiki";

    private final GlobalAdminGuard globalAdmin;
    private final AdminStatsRepository stats;

    private final Cache<String, WikiAdminStats> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(1)
            .build();

    /**
     * 트랜잭션을 메서드 전체에 건 이유: 집계 아홉 개가 같은 읽기 스냅숏에서 나와야 서로
     * 어긋나지 않는다(문서 수와 초안 수가 다른 시점의 것이면 화면이 이상해진다).
     */
    @Transactional(readOnly = true)
    public WikiAdminStats stats(long userId) {
        globalAdmin.require(userId, "플랫폼 현황은 전역 관리자만 볼 수 있습니다");
        return cache.get(KEY, key -> compute());
    }

    private WikiAdminStats compute() {
        return new WikiAdminStats(
                stats.countSpaces(),
                stats.countPages(),
                stats.countPagesByStatus(PageStatus.DRAFT),
                stats.countTrashedPages(),
                stats.countRevisions(),
                stats.countAttachmentsByStatus(AttachmentLifecycleStatus.CONFIRMED),
                stats.sumAttachmentBytesByStatus(AttachmentLifecycleStatus.CONFIRMED),
                stats.countRevisionsSince(Instant.now().minus(EDIT_WINDOW)),
                stats.countComments());
    }

    /**
     * 테스트 격리용 — 캐시를 비운다. 운영 경로에는 무효화 지점이 없다(TTL로만 만료된다):
     * 60초 늦은 숫자는 대시보드에서 문제가 아니고, 쓰기 경로마다 무효화를 심으면 그쪽이
     * 이 화면 때문에 느려진다.
     */
    void invalidate() {
        cache.invalidateAll();
    }
}
