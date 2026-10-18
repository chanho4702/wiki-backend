package com.platform.wikibackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 사용자별 알림 설정(V29) — 이메일 채널을 어떤 알림에 켤지.
 *
 * 행이 없으면 기본값(모두 켜짐)이다. 주소는 토큰의 email 클레임을 마지막으로 본 값으로 남긴다 —
 * 발송 시점에 org 디렉터리를 부르지 않기 위해서다.
 */
@Entity
@Table(name = "notification_pref")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPref {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(length = 255)
    private String email;

    /** 바로 보낼지, 하루 한 번 모아 보낼지(V31). */
    public enum EmailMode { IMMEDIATE, DAILY }

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "email_mode", nullable = false, length = 16)
    private EmailMode emailMode = EmailMode.IMMEDIATE;

    @Column(name = "on_mentioned", nullable = false)
    private boolean onMentioned = true;

    @Column(name = "on_page_updated", nullable = false)
    private boolean onPageUpdated = true;

    @Column(name = "on_comment", nullable = false)
    private boolean onComment = true;

    @Column(name = "on_shared", nullable = false)
    private boolean onShared = true;

    /** 새 문서 게시(V43) — 예전에는 on_page_updated를 함께 썼다. 마이그레이션이 그 값을 이어받았다. */
    @Column(name = "on_page_published", nullable = false)
    private boolean onPagePublished = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public static NotificationPref defaultsFor(long userId, String email) {
        NotificationPref p = new NotificationPref();
        p.userId = userId;
        p.email = normalize(email);
        return p;
    }

    /** 토큰의 주소가 바뀌었으면 따라간다(이름 변경·계정 이전). 비었으면 알던 값을 지우지 않는다. */
    public boolean rememberEmail(String email) {
        String next = normalize(email);
        if (next == null || next.equals(this.email)) return false;
        this.email = next;
        this.updatedAt = Instant.now();
        return true;
    }

    public void update(boolean emailEnabled, EmailMode mode, boolean onMentioned, boolean onPageUpdated,
                       boolean onComment, boolean onShared) {
        update(emailEnabled, mode, onMentioned, onPageUpdated, onComment, onShared, onPageUpdated);
    }

    public void update(boolean emailEnabled, EmailMode mode, boolean onMentioned, boolean onPageUpdated,
                       boolean onComment, boolean onShared, boolean onPagePublished) {
        this.onPagePublished = onPagePublished;
        this.emailEnabled = emailEnabled;
        this.emailMode = mode == null ? EmailMode.IMMEDIATE : mode;
        this.onMentioned = onMentioned;
        this.onPageUpdated = onPageUpdated;
        this.onComment = onComment;
        this.onShared = onShared;
        this.updatedAt = Instant.now();
    }

    /** 이 타입의 이메일을 원하는가 — 채널 스위치와 타입 스위치 둘 다 켜져 있어야 한다. */
    public boolean wants(Notification.Type type) {
        if (!emailEnabled) return false;
        return switch (type) {
            case MENTIONED -> onMentioned;
            case PAGE_UPDATED -> onPageUpdated;
            case COMMENT -> onComment;
            case SHARED -> onShared;
            // 새 문서 게시(W27-4)는 V43부터 따로 끈다(F-05) — "새 문서만 끄고 수정 알림은 받기".
            case PAGE_PUBLISHED -> onPagePublished;
        };
    }

    private static String normalize(String email) {
        if (email == null) return null;
        String t = email.trim();
        return t.isEmpty() || t.length() > 255 ? null : t;
    }
}
