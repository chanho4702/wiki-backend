package com.platform.wikibackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "page")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false, updatable = false)
    private Long spaceId;

    @Column(name = "parent_id")
    private Long parentId; // NULL = 루트

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private Integer version; // 낙관적 잠금 겸 현재 버전 번호 — 수동 관리(@Version 아님)

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Page of(Long spaceId, Long parentId, String title, String content, Long authorId) {
        Page p = new Page();
        p.spaceId = spaceId;
        p.parentId = parentId;
        p.title = title;
        p.content = content;
        p.version = 1;
        p.createdBy = authorId;
        p.updatedBy = authorId;
        return p;
    }

    /** 저장 = 새 버전. 호출부(서비스)가 expectedVersion 검사 후 호출한다. */
    public void edit(String title, String content, Long editorId) {
        this.title = title;
        this.content = content;
        this.version = this.version + 1;
        this.updatedBy = editorId;
    }

    public void moveTo(Long newParentId) {
        this.parentId = newParentId;
    }
}
