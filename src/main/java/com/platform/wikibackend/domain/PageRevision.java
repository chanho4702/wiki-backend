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

    /**
     * 평문 본문 — V16 이전 행과, 압축 임계값 미만인 짧은 본문만 여기 남는다.
     * 읽을 땐 항상 {@link #getContent()}를 쓴다(어느 쪽에 있는지 호출부가 알 필요 없다).
     */
    @Column(name = "content", updatable = false, columnDefinition = "text")
    private String contentText;

    /** gzip 압축 본문(V16). 저장할 때마다 본문 전체가 복사되는 구조라 크기가 그대로 비용이다. */
    @Column(name = "content_gzip", updatable = false)
    private byte[] contentGzip;

    @Column(name = "edited_by", nullable = false, updatable = false)
    private Long editedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** 평문이든 압축본이든 본문 하나로 돌려준다 — 저장 형식은 호출부의 관심사가 아니다. */
    public String getContent() {
        return contentGzip != null ? RevisionContent.decompress(contentGzip) : contentText;
    }

    /** 페이지의 현재 상태를 스냅샷 — "모든 버전이 리비전에 있다" 불변식의 단일 진입점. */
    public static PageRevision snapshotOf(Page page) {
        PageRevision r = new PageRevision();
        r.pageId = page.getId();
        r.version = page.getVersion();
        r.title = page.getTitle();
        String content = page.getContent();
        if (RevisionContent.shouldCompress(content)) {
            r.contentGzip = RevisionContent.compress(content);
        } else {
            r.contentText = content;
        }
        r.editedBy = page.getUpdatedBy();
        return r;
    }
}
