package com.platform.wikibackend.search;

import java.util.List;

/**
 * totalExact는 total이 정확한 수인지 알린다.
 *
 * 라이트 검색은 보통 정확하다(권한을 SQL에서 적용하고 센다). 제한 문서가 아주 많은 설치에서만
 * 키를 상한까지 읽어 거르므로, 상한을 넘으면 total은 "적어도 이만큼"이다. 화면이 "N건 이상"으로
 * 표현할 수 있게 사실대로 내려보낸다.
 */
public record SearchResults(int total, boolean totalExact, int tookMs, List<SearchHit> hits) {
    public static SearchResults empty() {
        return new SearchResults(0, true, 0, List.of());
    }
}
