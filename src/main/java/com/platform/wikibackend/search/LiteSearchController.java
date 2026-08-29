package com.platform.wikibackend.search;

import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import static com.platform.wikibackend.space.SpaceController.userId;

/**
 * 라이트 검색 GraphQL — `POST /graphql`.
 *
 * OpenSearch를 띄우지 않는 배포에서 게이트웨이의 `/api/search/**`가 search-service 대신 이리로
 * 온다(StripPrefix=2 → `/graphql`). 어느 쪽이 서비스할지는 게이트웨이의 `SEARCH_SERVICE_URI`
 * 하나로 정한다 — 두 곳에 스위치를 두면 반쪽만 바뀐 배포가 생긴다.
 *
 * OpenSearch 배포에서도 이 엔드포인트는 살아 있지만 아무도 부르지 않는다. 인증과 권한 판정은
 * 같은 규칙을 타므로 노출 자체가 문제가 되지는 않는다.
 */
@Controller
@RequiredArgsConstructor
public class LiteSearchController {

    private final LiteSearchService search;

    @QueryMapping
    public SearchResults search(@AuthenticationPrincipal Jwt jwt, @Argument SearchInput input) {
        return search.search(userId(jwt), input);
    }
}
