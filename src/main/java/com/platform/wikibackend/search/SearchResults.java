package com.platform.wikibackend.search;

import java.util.List;

/**
 * totalExact는 total이 정확한 수인지 알린다.
 *
 * 권한 후필터가 후보 창(window) 안에서만 돌기 때문에, 후보를 창 끝까지 채운 경우 total은
 * "적어도 이만큼"이다. 화면이 "약 N건"으로 표현할 수 있게 사실대로 내려보낸다.
 */
public record SearchResults(int total, boolean totalExact, int tookMs, List<SearchHit> hits) {
    public static SearchResults empty() {
        return new SearchResults(0, true, 0, List.of());
    }
}
