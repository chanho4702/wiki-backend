package com.platform.wikibackend.page.dto;

import java.util.List;

/**
 * 페이지의 조상 경로 — 검색 결과가 "어디에 있는 문서인지"를 보여주려고 쓴다.
 *
 * 자기 자신은 빼고 루트부터 부모까지다. 결과 목록은 제목을 이미 크게 그리므로 경로에 또 넣으면
 * 같은 말이 두 번 나온다. 루트 문서면 빈 목록이다.
 *
 * 이 조회를 검색 색인이 아니라 별도 API로 둔 이유: 색인에 경로를 넣으면 문서를 옮길 때마다
 * 그 하위 전체를 다시 색인해야 하고, 무엇보다 OpenSearch 배포와 라이트 배포가 서로 다른 길을
 * 타게 된다. 여기로 물어보면 두 배포가 같은 답을 낸다.
 */
public record PagePath(Long id, List<String> titles) {
}
