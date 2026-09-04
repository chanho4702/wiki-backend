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

    /**
     * 변경 요약(V17) — 선택 입력. 비어 있으면 null이다(빈 문자열로 저장하지 않는다).
     * 강제하면 "수정"만 적힌 이력이 쌓여 오히려 신호가 죽는다.
     */
    @Column(name = "change_note", length = 500, updatable = false)
    private String changeNote;

    /** 저장 시점 편집자 표시명(V28). 디렉터리에서 사라진 사람도 이름으로 남는다. null이면 화면이 id로 폴백한다. */
    @Column(name = "edited_by_name", length = 120)
    private String editedByName;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** 공백만 있는 요약은 없는 것과 같다 — 화면이 빈 칩을 그리지 않도록 null로 눕힌다. */
    private static String normalizeNote(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 평문이든 압축본이든 본문 하나로 돌려준다 — 저장 형식은 호출부의 관심사가 아니다. */
    public String getContent() {
        return contentGzip != null ? RevisionContent.decompress(contentGzip) : contentText;
    }

    /** 페이지의 현재 상태를 스냅샷 — "모든 버전이 리비전에 있다" 불변식의 단일 진입점. */
    public static PageRevision snapshotOf(Page page) {
        return snapshotOf(page, null);
    }

    /**
     * 이미 뜬 스냅샷의 본문만 바꾼다(W29 M2 첨부 참조 정리).
     *
     * 문서 본문을 새 버전 없이 눌렀다면 이력도 같이 눌러야 한다 — 안 그러면 "현재"와 같은 번호의
     * 리비전이 서로 다른 본문을 들고 있어, 복원이 첨부 참조를 되돌려 깨뜨린다.
     */
    public void replaceContent(String content) {
        if (RevisionContent.shouldCompress(content)) {
            this.contentGzip = RevisionContent.compress(content);
            this.contentText = null;
        } else {
            this.contentText = content;
            this.contentGzip = null;
        }
    }

    /** 편집자 이름을 붙인다 — 팩토리는 Page만 알고 Page는 이름을 모르므로 호출부가 얹는다. */
    public PageRevision withEditorName(String name) {
        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            this.editedByName = trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
        }
        return this;
    }

    /** 변경 요약과 함께 스냅샷 — 사용자가 저장 시 남긴 한 줄이 있을 때. */
    public static PageRevision snapshotOf(Page page, String changeNote) {
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
        r.changeNote = normalizeNote(changeNote);
        return r;
    }
}
