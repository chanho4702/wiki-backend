package com.platform.wikibackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** 페이지 제한 한 줄 — 의미론은 EffectivePermissionService(W18 설계서) 참조. */
@Entity
@Table(name = "page_restriction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageRestriction {

    public enum Type { VIEW, EDIT }

    public enum PrincipalType { USER, TEAM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "page_id", nullable = false)
    private Long pageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 8)
    private PrincipalType principalType;

    @Column(name = "principal_id", nullable = false)
    private Long principalId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static PageRestriction of(long pageId, Type type, PrincipalType principalType,
                                     long principalId, long createdBy) {
        PageRestriction r = new PageRestriction();
        r.pageId = pageId;
        r.type = type;
        r.principalType = principalType;
        r.principalId = principalId;
        r.createdBy = createdBy;
        return r;
    }
}
