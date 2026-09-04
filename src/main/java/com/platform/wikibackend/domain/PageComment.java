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

    /** PAGE = 페이지 하단 댓글, INLINE = 본문 구간에 붙은 댓글(V15). */
    @Column(name = "anchor_type", nullable = false, length = 16, updatable = false)
    private String anchorType;

    /**
     * 인라인 댓글이 붙은 본문 텍스트(V15). 블록 id가 아니라 인용문인 이유: 저장 형식이 마크다운
     * 문자열이라 안정적인 블록 식별자가 없다. 본문이 바뀌어 못 찾으면 스레드를 "위치 없음"으로 남긴다.
     */
    @Column(name = "anchor_quote", columnDefinition = "text", updatable = false)
    private String anchorQuote;

    /** 같은 인용문이 본문에 여러 번 나올 때 몇 번째인지(0부터). */
    @Column(name = "anchor_occurrence", updatable = false)
    private Integer anchorOccurrence;

    /** 해결된 스레드는 본문 하이라이트에서 내려간다. 재개하면 null로 돌아간다. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

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

    /**
     * 외부 위키에서 옮겨온 댓글(W29 M3 §5.2).
     *
     * 원본 댓글은 전부 **페이지 댓글**로 들어온다 — 원본의 인라인 댓글은 앵커가 원본 렌더 기준이라
     * 우리 본문에서 같은 구간을 다시 찾을 방법이 없다. 대신 인용을 본문 앞에 붙여 옮긴다.
     *
     * createdAt은 여기서 넣어도 @CreationTimestamp가 INSERT에서 "지금"으로 덮어쓴다 —
     * 실제 보존은 저장 뒤 {@code PageCommentRepository.overwriteCreatedAt}이 완성한다.
     */
    public static PageComment imported(Long pageId, Long parentId, Long authorId, String authorName,
                                       String body, Instant createdAt) {
        PageComment comment = of(pageId, parentId, authorId, authorName, body);
        comment.createdAt = createdAt;
        comment.updatedAt = createdAt;
        return comment;
    }

    /** 인라인 댓글 — 앵커는 만들 때 정해지고 이후 바뀌지 않는다(본문이 바뀌면 못 찾을 뿐). */
    public static PageComment inlineOf(Long pageId, Long authorId, String authorName, String body,
                                       String anchorQuote, int anchorOccurrence) {
        PageComment comment = of(pageId, null, authorId, authorName, body);
        comment.anchorType = "INLINE";
        comment.anchorQuote = anchorQuote;
        comment.anchorOccurrence = anchorOccurrence;
        return comment;
    }

    public boolean isInline() {
        return "INLINE".equals(anchorType);
    }

    /** 해결/재개는 본문 수정이 아니므로 editedAt을 건드리지 않는다. */
    public void resolve(long userId, Instant now) {
        this.resolvedAt = now;
        this.resolvedBy = userId;
    }

    public void reopen() {
        this.resolvedAt = null;
        this.resolvedBy = null;
    }

    public void edit(String body, Instant now) {
        this.body = body;
        this.editedAt = now;
    }
}
