package com.platform.wikibackend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `/v3/api-docs`가 문서 생성기(myFront `scripts/api`)가 쓸 수 있는 모양으로 나오는지 지킨다.
 *
 * 태그·요약이 비면 생성기가 "제목 없는 엔드포인트"를 뱉는데, 그건 실행해 보기 전에는 드러나지
 * 않는다. 그래서 새 컨트롤러가 주석 없이 들어오면 여기서 먼저 깨지게 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenApiDocsTest {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private JsonNode spec() throws Exception {
        // 토큰 없이 200이어야 한다 — 수집기는 인증 없이 컨테이너 네트워크에서 긁어 간다.
        String body = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new ObjectMapper().readTree(body);
    }

    @Test
    void 모든_오퍼레이션에_태그와_요약이_있다() throws Exception {
        JsonNode paths = spec().get("paths");
        assertThat(paths).isNotNull();

        List<String> missing = new ArrayList<>();
        paths.properties().forEach(path -> path.getValue().properties().forEach(op -> {
            if (!HTTP_METHODS.contains(op.getKey())) {
                return;
            }
            JsonNode operation = op.getValue();
            String where = op.getKey().toUpperCase() + " " + path.getKey();
            JsonNode tags = operation.get("tags");
            if (tags == null || !tags.isArray() || tags.isEmpty()) {
                missing.add(where + " — @Tag 없음");
            }
            JsonNode summary = operation.get("summary");
            if (summary == null || summary.asText().isBlank()) {
                missing.add(where + " — @Operation(summary) 없음");
            }
        }));

        assertThat(missing).as("주석이 빠진 오퍼레이션").isEmpty();
    }

    @Test
    void 문서에_들어간_오퍼레이션이_비어_있지_않다() throws Exception {
        JsonNode paths = spec().get("paths");
        long operations = 0;
        for (var path : paths.properties()) {
            for (var op : path.getValue().properties()) {
                if (HTTP_METHODS.contains(op.getKey())) {
                    operations++;
                }
            }
        }
        // 컨트롤러가 통째로 스캔에서 빠지는 회귀(예: springdoc 패키지 스캔 설정 실수)를 잡는다.
        assertThat(operations).isGreaterThan(70);
    }

    @Test
    void 내부_전용_경로는_문서에_없다() throws Exception {
        List<String> leaked = new ArrayList<>();
        spec().get("paths").fieldNames().forEachRemaining(path -> {
            // gRPC 내부 계약과 라이트 검색 GraphQL은 외부 문서의 대상이 아니다.
            if (path.startsWith("/internal") || path.startsWith("/graphql")) {
                leaked.add(path);
            }
        });
        assertThat(leaked).as("문서에 샌 내부 경로").isEmpty();
    }

    @Test
    void 모든_경로는_api_wiki_아래에_있다() throws Exception {
        List<String> outside = new ArrayList<>();
        spec().get("paths").fieldNames().forEachRemaining(path -> {
            if (!path.startsWith("/api/wiki/")) {
                outside.add(path);
            }
        });
        assertThat(outside).as("/api/wiki 밖으로 나간 경로").isEmpty();
    }

    @Test
    void bearerAuth_보안_스킴이_전역으로_걸린다() throws Exception {
        JsonNode spec = spec();

        JsonNode scheme = spec.at("/components/securitySchemes/bearerAuth");
        assertThat(scheme.isMissingNode()).isFalse();
        assertThat(scheme.get("type").asText()).isEqualTo("http");
        assertThat(scheme.get("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.get("description").asText()).contains("chanho_pat_");

        JsonNode security = spec.get("security");
        assertThat(security).isNotNull();
        assertThat(security.isArray()).isTrue();
        assertThat(security.toString()).contains("bearerAuth");
    }

    @Test
    void 공통_오류_스키마와_401_403이_붙는다() throws Exception {
        JsonNode spec = spec();
        assertThat(spec.at("/components/schemas/PlatformError/properties/error/type").asText())
                .isEqualTo("string");

        JsonNode get = spec.at("/paths/~1api~1wiki~1spaces/get");
        assertThat(get.isMissingNode()).isFalse();
        assertThat(get.at("/responses/401").isMissingNode()).isFalse();
        assertThat(get.at("/responses/403").isMissingNode()).isFalse();
        // 목록 조회에는 404를 붙이지 않는다.
        assertThat(get.at("/responses/404").isMissingNode()).isTrue();

        // 경로 변수로 대상을 지목하면 404가 붙는다.
        assertThat(spec.at("/paths/~1api~1wiki~1spaces~1{id}/get/responses/404").isMissingNode())
                .isFalse();

        // 낙관적 락(expectedVersion)이 있는 PUT에만 409가 붙는다.
        assertThat(spec.at("/paths/~1api~1wiki~1pages~1{id}/put/responses/409").isMissingNode())
                .isFalse();
        assertThat(spec.at("/paths/~1api~1wiki~1spaces~1{id}/put/responses/409").isMissingNode())
                .isTrue();
    }

    @Test
    void 서비스_메타가_스펙에_담긴다() throws Exception {
        JsonNode spec = spec();
        assertThat(spec.at("/info/title").asText()).isEqualTo("WIKI API");
        assertThat(spec.at("/info/version").asText()).isNotBlank();
        assertThat(spec.at("/servers/0/url").asText()).isEqualTo("/");
    }
}
