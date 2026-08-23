package com.platform.wikibackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** 알림 한 건 — 수신자 기준. 타입·합침 규칙은 NotificationService 참조. */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    public enum Type { MENTIONED, PAGE_UPDATED, COMMENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    @Column(name = "page_id", nullable = false)
    private Long pageId;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    public static Notification of(long userId, Type type, long pageId, long actorId) {
        Notification n = new Notification();
        n.userId = userId;
        n.type = type;
        n.pageId = pageId;
        n.actorId = actorId;
        return n;
    }

    public boolean isRead() {
        return readAt != null;
    }

    public void markRead(Instant at) {
        if (readAt == null) readAt = at;
    }

    /** 미읽음 합침 — 최신 행위자·시각으로 당긴다(새 행 대신). */
    public void refresh(long actorId, Instant at) {
        this.actorId = actorId;
        this.createdAt = at;
    }
}
