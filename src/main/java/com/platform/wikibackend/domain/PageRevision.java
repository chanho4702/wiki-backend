package com.platform.wikibackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "page_revision",
        uniqueConstraints = @UniqueConstraint(columnNames = {"page_id", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "page_id", nullable = false, updatable = false)
    private Long pageId;

    @Column(nullable = false, updatable = false)
    private Integer version;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "edited_by", nullable = false, updatable = false)
    private Long editedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** 페이지의 현재 상태를 스냅샷 — "모든 버전이 리비전에 있다" 불변식의 단일 진입점. */
    public static PageRevision snapshotOf(Page page) {
        PageRevision r = new PageRevision();
        r.pageId = page.getId();
        r.version = page.getVersion();
        r.title = page.getTitle();
        r.content = page.getContent();
        r.editedBy = page.getUpdatedBy();
        return r;
    }
}
