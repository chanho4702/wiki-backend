package com.platform.wikibackend.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 하이라이트 조각 — 검색어 주변을 잘라내고 `<em>`으로 감싼다.
 *
 * OpenSearch의 highlighter를 대신하는 최소 구현이다. 프론트(SearchHighlights)가 `<em>`만
 * 해석하므로 태그는 그것 하나로 맞춘다. 사용자 입력이 그대로 마크업에 섞이지 않도록 조각을
 * 만들 때 HTML 특수문자를 먼저 이스케이프한다 — 본문에 `<script>`가 있어도 문자로 남는다.
 */
final class Snippets {

    /** 조각 하나의 길이. 검색어 앞뒤로 이만큼씩 더 보여준다. */
    private static final int CONTEXT = 60;

    private Snippets() {}

    static List<String> highlights(String query, String title, String content, String filename) {
        List<String> out = new ArrayList<>();
        for (String source : new String[] {title, filename, content}) {
            String snippet = snippet(source, query);
            if (snippet != null) out.add(snippet);
        }
        return List.copyOf(out);
    }

    private static String snippet(String source, String query) {
        if (source == null || source.isEmpty()) return null;
        int at = source.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (at < 0) return null;

        int from = Math.max(at - CONTEXT, 0);
        int to = Math.min(at + query.length() + CONTEXT, source.length());
        String before = escape(source.substring(from, at));
        String match = escape(source.substring(at, at + query.length()));
        String afterText = escape(source.substring(at + query.length(), to));

        return (from > 0 ? "…" : "") + before + "<em>" + match + "</em>" + afterText
                + (to < source.length() ? "…" : "");
    }

    private static String escape(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
