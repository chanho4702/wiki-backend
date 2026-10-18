package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /** 전체 알림 화면(F-05) — 최신순 첫 쪽, 종류 필터. 커서는 별도 질의(null 파라미터 타입 추론을 피한다). */
    @org.springframework.data.jpa.repository.Query(
            "select n from Notification n where n.userId = :userId and n.type in :types order by n.id desc")
    List<Notification> findPage(@org.springframework.data.repository.query.Param("userId") long userId,
                                @org.springframework.data.repository.query.Param("types") java.util.Collection<Notification.Type> types,
                                org.springframework.data.domain.Limit limit);

    /** 다음 쪽 — id 커서(이 id보다 오래된 것). */
    @org.springframework.data.jpa.repository.Query(
            "select n from Notification n where n.userId = :userId and n.type in :types and n.id < :before order by n.id desc")
    List<Notification> findPageBefore(@org.springframework.data.repository.query.Param("userId") long userId,
                                      @org.springframework.data.repository.query.Param("types") java.util.Collection<Notification.Type> types,
                                      @org.springframework.data.repository.query.Param("before") long before,
                                      org.springframework.data.domain.Limit limit);
    long countByUserIdAndReadAtIsNull(Long userId);
    Optional<Notification> findFirstByUserIdAndPageIdAndTypeAndReadAtIsNull(
            Long userId, Long pageId, Notification.Type type);
    List<Notification> findByUserIdAndReadAtIsNull(Long userId);

    /**
     * 안 읽은 알림을 페이지별로 센다 — 알림함 배지가 "볼 수 있는 것만" 세려면 페이지 단위 판정이 필요하지만
     * 알림 행 전량을 엔티티로 읽을 이유는 없다(감사 W-11). 행 수는 서로 다른 페이지 수로 묶인다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select n.pageId as pageId, count(n) as count from Notification n
             where n.userId = :userId and n.readAt is null
             group by n.pageId
            """)
    List<PageCount> countUnreadByPage(@org.springframework.data.repository.query.Param("userId") long userId);

    interface PageCount {
        Long getPageId();
        Long getCount();
    }

    /** 전체 읽음 — 한 문장. 안 읽은 알림 전량을 읽어 하나씩 고치지 않는다. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(@org.springframework.data.repository.query.Param("userId") long userId,
                    @org.springframework.data.repository.query.Param("now") java.time.Instant now);
    List<Notification> findByIdInAndUserId(List<Long> ids, Long userId);
    /** 요약 메일 대상 — 아직 메일로 나가지 않은 최근 알림(읽음 여부와 무관: 요약은 "그동안 있었던 일"이다). */
    List<Notification> findByUserIdAndEmailedAtIsNullAndCreatedAtAfterOrderByCreatedAtAsc(Long userId, java.time.Instant since);
}
