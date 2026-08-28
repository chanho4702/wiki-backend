package com.platform.wikibackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Locale;

/**
 * 내부 링크 그래프(V14) — 백링크("이 페이지를 링크한 문서")의 원장.
 * 대상은 id가 아니라 제목이다: 이 위키의 `[[제목]]`은 렌더 시점에 같은 스페이스의 제목으로
 * 해석되므로, 그래프도 같은 기준이어야 화면과 어긋나지 않는다.
 */
@Entity
@Table(name = "page_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_page_id", nullable = false)
    private Long sourcePageId;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "target_title", nullable = false)
    private String targetTitle;

    public static PageLink of(long sourcePageId, long spaceId, String targetTitle) {
        PageLink link = new PageLink();
        link.sourcePageId = sourcePageId;
        link.spaceId = spaceId;
        link.targetTitle = normalizeTitle(targetTitle);
        return link;
    }

    /** 제목 대조 기준 — wiki-front resolveWikiLinks와 같다(trim + 소문자). */
    public static String normalizeTitle(String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
    }
}
