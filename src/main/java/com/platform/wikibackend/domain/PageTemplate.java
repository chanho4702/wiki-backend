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
import java.util.Locale;

/**
 * 페이지 템플릿 — 그 스페이스가 합의한 문서 형태(W23).
 *
 * 본문은 페이지와 같은 마크다운 문자열이다. 별도 표현을 두면 템플릿에서 만든 문서와 직접 쓴
 * 문서가 서로 다른 저장 포맷을 갖게 된다.
 */
@Entity
@Table(name = "page_template")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageTemplate {

    public static final int MAX_NAME = 100;
    public static final int MAX_DESCRIPTION = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false, updatable = false)
    private Long spaceId;

    @Column(nullable = false, length = MAX_NAME)
    private String name;

    @Column(length = MAX_DESCRIPTION)
    private String description;

    @Column(length = 16)
    private String icon;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static PageTemplate of(long spaceId, String name, String description, String icon,
                                  String content, long authorId) {
        PageTemplate t = new PageTemplate();
        t.spaceId = spaceId;
        t.createdBy = authorId;
        t.apply(name, description, icon, content, authorId);
        return t;
    }

    public void apply(String name, String description, String icon, String content, long editorId) {
        this.name = normalizeName(name);
        this.description = blankToNull(description, MAX_DESCRIPTION);
        this.icon = blankToNull(icon, 16);
        this.content = content == null ? "" : content;
        this.updatedBy = editorId;
    }

    /** 이름은 화면에서 고르는 유일한 단서다 — 앞뒤 공백과 연속 공백을 정리해 사실상 같은 이름을 막는다. */
    private static String normalizeName(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("\s+", " ");
        if (value.isEmpty()) throw new IllegalArgumentException("템플릿 이름을 입력하세요");
        if (value.length() > MAX_NAME) {
            throw new IllegalArgumentException("템플릿 이름은 " + MAX_NAME + "자를 넘을 수 없습니다");
        }
        return value;
    }

    private static String blankToNull(String raw, int max) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (value.length() > max) {
            throw new IllegalArgumentException("입력이 " + max + "자를 넘습니다");
        }
        return value;
    }

    /** 목록 정렬 기준 — 대소문자를 섞어 만든 이름이 뒤죽박죽 놓이지 않게 한다. */
    public String sortKey() {
        return name.toLowerCase(Locale.ROOT);
    }
}
