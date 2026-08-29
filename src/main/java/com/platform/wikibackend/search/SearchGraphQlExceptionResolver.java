package com.platform.wikibackend.search;

import com.platform.wikibackend.common.ServiceUnavailableException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 라이트 검색의 오류 계약 — search-service의 GraphQlExceptionResolver와 같은 모양을 낸다.
 *
 * 프론트는 `extensions.httpStatus`·`code`를 보고 429·503·400을 서로 다른 복구 안내로 그린다.
 * 두 배포에서 같은 실패가 다른 모양으로 오면 그 분기가 한쪽에서만 동작한다.
 *
 * DataFetcher 실패는 GraphQL 전송 자체의 실패가 아니므로 HTTP는 200을 유지하고, 의미는
 * extensions에 싣는다.
 */
@Component
public class SearchGraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
        if (exception instanceof ServiceUnavailableException unavailable) {
            return error(environment, unavailable.getMessage(), "SERVICE_UNAVAILABLE", 503);
        }
        // 잘못된 기간·ID 형식 — 조용히 빈 결과로 삼키지 않는다(원인을 찾을 수 없게 된다).
        if (exception instanceof IllegalArgumentException bad) {
            return error(environment, bad.getMessage(), "BAD_REQUEST", 400);
        }
        return null;
    }

    private static GraphQLError error(DataFetchingEnvironment env, String message, String code, int status) {
        return GraphqlErrorBuilder.newError(env)
                .message(message)
                .extensions(Map.of("code", code, "httpStatus", status))
                .build();
    }
}
