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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 페이지 댓글. 중첩은 1단(답글의 답글 금지)이며 그 규칙은 서비스가 검증한다.
 * authorName은 작성 시점 표시 이름 스냅샷 — 사용자 디렉터리 연동 전에도 화면이 이름을 보여준다.
 */
@Entity
@Table(name = "page_comment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "page_id", nullable = false, updatable = false)
    private Long pageId;

    /** NULL = 최상위. 값이 있으면 답글이며 대상은 반드시 최상위 댓글이다. */
    @Column(name = "parent_id", updatable = false)
    private Long parentId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private Long authorId;

    @Column(name = "author_name", nullable = false, length = 120, updatable = false)
    private String authorName;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** 인라인 댓글 확장 자리 — 지금은 PAGE 고정. */
    @Column(name = "anchor_type", nullable = false, length = 16, updatable = false)
    private String anchorType;

    /** 수정된 적 없으면 null — 프론트 "(수정됨)" 표시 근거. updatedAt(감사용)과 구분한다. */
    @Column(name = "edited_at")
    private Instant editedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static PageComment of(Long pageId, Long parentId, Long authorId, String authorName, String body) {
        PageComment comment = new PageComment();
        comment.pageId = pageId;
        comment.parentId = parentId;
        comment.authorId = authorId;
        comment.authorName = authorName;
        comment.body = body;
        comment.anchorType = "PAGE";
        return comment;
    }

    public void edit(String body, Instant now) {
        this.body = body;
        this.editedAt = now;
    }
}
