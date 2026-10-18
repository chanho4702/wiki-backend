package com.platform.wikibackend.common;

/**
 * LIKE 패턴 이스케이프 — 사용자가 친 %·_·\\를 와일드카드가 아니라 글자로 찾게 한다.
 *
 * "50%"나 "snake_case"를 검색하면 %·_가 와일드카드로 해석돼 엉뚱한 결과가 나오고, "%" 한 글자 검색은
 * 전 문서를 훑었다(trgm 인덱스 무력화, 감사 W-20). 질의 쪽은 모두 {@code escape '\\'}를 붙인다.
 */
public final class LikePatterns {

    private LikePatterns() {
    }

    public static String escape(String raw) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (char c : raw.toCharArray()) {
            if (c == '\\' || c == '%' || c == '_') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    /** 부분 일치 패턴 — %이스케이프된 값%. */
    public static String contains(String raw) {
        return "%" + escape(raw) + "%";
    }
}
