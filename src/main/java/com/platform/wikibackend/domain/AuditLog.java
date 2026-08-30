package com.platform.wikibackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 되돌리기 어려운 조작의 흔적(W23).
 *
 * 대상의 **이름을 함께 저장한다**. id만 남기면 지워진 문서의 기록이 숫자만 남아 아무도 못
 * 알아본다 — 감사 로그가 읽히려면 그때 그 이름이 필요하다.
 */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    public static final int MAX_LABEL = 255;
    public static final int MAX_DETAIL = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false, updatable = false)
    private Long spaceId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Column(nullable = false, length = 40, updatable = false)
    private String action;

    @Column(name = "target_type", nullable = false, length = 20, updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Column(name = "target_label", nullable = false, length = MAX_LABEL, updatable = false)
    private String targetLabel;

    @Column(length = MAX_DETAIL, updatable = false)
    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static AuditLog of(long spaceId, long actorId, AuditAction action, String targetType,
                              Long targetId, String targetLabel, String detail) {
        AuditLog log = new AuditLog();
        log.spaceId = spaceId;
        log.actorId = actorId;
        log.action = action.name();
        log.targetType = targetType;
        log.targetId = targetId;
        log.targetLabel = clamp(targetLabel == null || targetLabel.isBlank() ? "(이름 없음)" : targetLabel, MAX_LABEL);
        log.detail = detail == null || detail.isBlank() ? null : clamp(detail, MAX_DETAIL);
        return log;
    }

    /** 기록이 길다고 조작을 실패시키지 않는다 — 자른 흔적을 남기고 계속한다. */
    private static String clamp(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
