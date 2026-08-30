package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
    long countByUserIdAndReadAtIsNull(Long userId);
    Optional<Notification> findFirstByUserIdAndPageIdAndTypeAndReadAtIsNull(
            Long userId, Long pageId, Notification.Type type);
    List<Notification> findByUserIdAndReadAtIsNull(Long userId);
    List<Notification> findByIdInAndUserId(List<Long> ids, Long userId);
    /** 요약 메일 대상 — 아직 메일로 나가지 않은 최근 알림(읽음 여부와 무관: 요약은 "그동안 있었던 일"이다). */
    List<Notification> findByUserIdAndEmailedAtIsNullAndCreatedAtAfterOrderByCreatedAtAsc(Long userId, java.time.Instant since);
}
