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

    public enum Type { MENTIONED, PAGE_UPDATED, COMMENT, SHARED }

    public static final int MAX_NOTE = 300;

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

    /** 메일로 나간 시각(V31). null이면 아직 — 요약 모드가 이 행을 모은다. */
    @Column(name = "emailed_at")
    private Instant emailedAt;

    /** 공유(SHARED)에만 있는 한마디 — "왜 봐야 하는지". 다른 타입은 null. */
    @Column(length = MAX_NOTE)
    private String note;

    public static Notification of(long userId, Type type, long pageId, long actorId) {
        return of(userId, type, pageId, actorId, null);
    }

    public static Notification of(long userId, Type type, long pageId, long actorId, String note) {
        Notification n = new Notification();
        n.userId = userId;
        n.type = type;
        n.pageId = pageId;
        n.actorId = actorId;
        n.note = note == null || note.isBlank() ? null : note.trim();
        return n;
    }

    public void markEmailed(Instant at) {
        if (emailedAt == null) emailedAt = at;
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
