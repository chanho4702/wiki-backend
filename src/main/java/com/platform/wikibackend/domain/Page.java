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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PageType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PageStatus status;

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
        return of(spaceId, parentId, title, content, authorId, PageType.PAGE, PageStatus.PUBLISHED);
    }

    public static Page of(Long spaceId, Long parentId, String title, String content, Long authorId,
                          PageType type, PageStatus status) {
        Page p = new Page();
        p.spaceId = spaceId;
        p.parentId = parentId;
        // 폴더는 게시 개념이 없다 — 초안으로 만들어달라는 요청이 와도 게시 상태로 고정한다(기획 P3)
        p.type = type == null ? PageType.PAGE : type;
        p.status = p.type == PageType.FOLDER ? PageStatus.PUBLISHED
                : (status == null ? PageStatus.PUBLISHED : status);
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

    /**
     * 복제 직후 첨부 참조(inline URL)를 사본 첨부로 바꿔치기한다 — 생성 초기화의 일부라
     * version을 올리지 않는다(edit와 구분). 일반 수정 경로에서 부르지 않는다.
     */
    public void rewriteContentForCopy(String content) {
        this.content = content;
    }

    public void moveTo(Long newParentId) {
        this.parentId = newParentId;
    }

    /**
     * 초안을 게시한다. 이미 게시된 문서면 아무것도 하지 않는다(멱등).
     * 게시는 내용 변경이 아니므로 version을 올리지 않는다 — 리비전도 쌓이지 않는다.
     */
    public void publish() {
        this.status = PageStatus.PUBLISHED;
    }
}
