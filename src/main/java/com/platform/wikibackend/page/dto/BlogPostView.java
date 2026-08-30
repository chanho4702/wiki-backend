package com.platform.wikibackend.page.dto;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStatus;

import java.time.Instant;

/** 블로그 목록 한 줄(W24) — 본문 대신 발췌를 준다. 목록에서 전문을 내리면 글 수만큼 무거워진다. */
public record BlogPostView(Long id, String title, PageStatus status, String icon,
                           Long createdBy, Long updatedBy, Instant createdAt, Instant updatedAt,
                           String excerpt) {

    static final int EXCERPT_LENGTH = 200;

    public static BlogPostView from(Page p) {
        return new BlogPostView(p.getId(), p.getTitle(), p.getStatus(), p.getIcon(),
                p.getCreatedBy(), p.getUpdatedBy(), p.getCreatedAt(), p.getUpdatedAt(),
                excerptOf(p.getContent()));
    }

    /** 마크다운 기호를 걷어낸 첫 200자 — 완벽한 렌더가 아니라 "무슨 글인지"만 보이면 된다. */
    public static String excerptOf(String markdown) {
        if (markdown == null) return "";
        String text = markdown
                .replaceAll("(?m)^\\s*:{2,}[^\\n]*$", " ")          // 지시자 줄(:::columns 등)
                .replaceAll("```[\\s\\S]*?```", " ")                 // 코드 블록
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")        // 이미지
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")     // 링크 → 텍스트
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")            // 헤딩 기호
                .replaceAll("(?m)^\\s*[-*+]\\s+\\[[ xX]\\]\\s*", "") // 체크박스
                .replaceAll("(?m)^\\s*[-*+>]\\s+", "")                // 목록·인용 기호
                .replaceAll("[*_`~|]", "")                          // 인라인 강조·표 기호
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() <= EXCERPT_LENGTH ? text : text.substring(0, EXCERPT_LENGTH).trim() + "…";
    }
}
