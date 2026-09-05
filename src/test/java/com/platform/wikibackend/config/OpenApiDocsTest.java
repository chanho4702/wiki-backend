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
import java.util.Map;
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

    private static final Map<String, String> CANONICAL = Map.of(
            "400", "요청 검증 실패",
            "401", "인증 실패 — 토큰 없음·만료·무효",
            "403", "권한 없음",
            "404", "대상 없음",
            "409", "버전 충돌 — expectedVersion 불일치",
            "503", "권한 서비스(org) 불능");
    private static final String ERROR_REF = "#/components/schemas/PlatformError";
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
        List<String> missing = new ArrayList<>();
        eachOperation(spec(), (where, operation) -> {
            JsonNode tags = operation.get("tags");
            if (tags == null || !tags.isArray() || tags.isEmpty()) {
                missing.add(where + " — @Tag 없음");
            }
            JsonNode summary = operation.get("summary");
            if (summary == null || summary.asText().isBlank()) {
                missing.add(where + " — @Operation(summary) 없음");
            }
        });

        assertThat(missing).as("주석이 빠진 오퍼레이션").isEmpty();
    }

    /** "METHOD /경로" 라벨과 오퍼레이션 노드를 짝지어 훑는다. */
    private static void eachOperation(JsonNode spec, java.util.function.BiConsumer<String, JsonNode> visit) {
        spec.get("paths").properties().forEach(path -> path.getValue().properties().forEach(op -> {
            if (HTTP_METHODS.contains(op.getKey())) {
                visit.accept(op.getKey().toUpperCase() + " " + path.getKey(), op.getValue());
            }
        }));
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

    /**
     * 인증 주체(@AuthenticationPrincipal Jwt)가 쿼리 파라미터로 새지 않는지.
     * 모든 핸들러가 Jwt를 받으므로, 한 번 새면 105개 오퍼레이션 전부에 가짜 파라미터가 붙는다.
     * alm-backend가 실제로 밟은 함정이라 여기서 회귀로 막는다.
     */
    @Test
    void 인증_주체가_파라미터로_새지_않는다() throws Exception {
        List<String> leaked = new ArrayList<>();
        eachOperation(spec(), (where, operation) -> {
            JsonNode parameters = operation.get("parameters");
            if (parameters == null) {
                return;
            }
            for (JsonNode parameter : parameters) {
                String name = parameter.path("name").asText("").toLowerCase();
                if (name.contains("jwt") || name.contains("principal")) {
                    leaked.add(where + " — " + name);
                }
            }
        });
        assertThat(leaked).as("인증 주체가 샌 파라미터").isEmpty();
    }

    /**
     * 성공 응답이 사라지지 않았는지.
     * 핸들러에 @ApiResponse를 하나라도 달면 springdoc이 반환 타입에서 200을 자동 생성하지 않는다 —
     * 4xx만 달면 성공 응답이 조용히 사라진다. 바이너리 응답 세 곳이 그 경계에 있다.
     */
    @Test
    void 모든_오퍼레이션에_성공_응답이_있다() throws Exception {
        List<String> missing = new ArrayList<>();
        eachOperation(spec(), (where, operation) -> {
            boolean success = false;
            var codes = operation.path("responses").fieldNames();
            while (codes.hasNext()) {
                if (codes.next().startsWith("2")) {
                    success = true;
                }
            }
            if (!success) {
                missing.add(where);
            }
        });
        assertThat(missing).as("2xx 응답이 없는 오퍼레이션").isEmpty();
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

    /**
     * 503은 전 오퍼레이션에 붙는다. 읽기든 쓰기든 org-service gRPC 권한 판정을 타므로,
     * org가 죽으면 어떤 엔드포인트든 503이 나갈 수 있다 — alm·org와 맞춘 플랫폼 공통 규칙.
     */
    @Test
    void 모든_오퍼레이션에_503이_붙는다() throws Exception {
        List<String> missing = new ArrayList<>();
        eachOperation(spec(), (where, operation) -> {
            JsonNode response = operation.path("responses").path("503");
            if (response.isMissingNode()) {
                missing.add(where);
            } else if (!ERROR_REF.equals(response.at("/content/application~1json/schema/$ref").asText())) {
                missing.add(where + " — PlatformError 스키마가 아님");
            }
        });
        assertThat(missing).as("503이 빠진 오퍼레이션").isEmpty();
    }

    /**
     * 400은 요청 본문이 있는 오퍼레이션에만. 본문 없는 GET·DELETE에는 검증할 것이 없다 —
     * 전부에 붙이면 생성된 문서가 "아무 요청이나 400이 날 수 있다"고 잘못 말한다.
     */
    @Test
    void 요청_본문이_있는_오퍼레이션에만_400이_붙는다() throws Exception {
        List<String> wrong = new ArrayList<>();
        eachOperation(spec(), (where, operation) -> {
            boolean hasBody = !operation.path("requestBody").isMissingNode();
            boolean has400 = !operation.path("responses").path("400").isMissingNode();
            if (hasBody && !has400) {
                wrong.add(where + " — 본문이 있는데 400 없음");
            }
            if (!hasBody && has400) {
                wrong.add(where + " — 본문이 없는데 400 있음");
            }
        });
        assertThat(wrong).as("400 규칙에 어긋난 오퍼레이션").isEmpty();

        // 규칙이 실제로 무언가를 덮는지 — 본문 있는 오퍼레이션이 0개면 위 단언은 공허하게 통과한다.
        JsonNode create = spec().at("/paths/~1api~1wiki~1pages/post");
        assertThat(create.at("/responses/400").isMissingNode()).isFalse();
        assertThat(spec().at("/paths/~1api~1wiki~1pages~1{id}/get/responses/400").isMissingNode()).isTrue();
    }

    /**
     * 오류 설명 문구가 세 서비스 공통값 그대로인지. 상수를 읽지 않고 리터럴로 대조한다 —
     * 상수를 참조하면 문구가 바뀔 때 테스트도 같이 따라가서 아무것도 못 잡는다.
     */
    @Test
    void 오류_설명_문구가_세_서비스_공통값이다() throws Exception {
        JsonNode spec = spec();

        JsonNode write = spec.at("/paths/~1api~1wiki~1pages/post/responses");
        assertThat(write.at("/400/description").asText()).isEqualTo("요청 검증 실패");
        assertThat(write.at("/401/description").asText()).isEqualTo("인증 실패 — 토큰 없음·만료·무효");
        assertThat(write.at("/403/description").asText()).isEqualTo("권한 없음");
        assertThat(write.at("/503/description").asText()).isEqualTo("권한 서비스(org) 불능");

        JsonNode put = spec.at("/paths/~1api~1wiki~1pages~1{id}/put/responses");
        assertThat(put.at("/404/description").asText()).isEqualTo("대상 없음");
        assertThat(put.at("/409/description").asText()).isEqualTo("버전 충돌 — expectedVersion 불일치");

        // 한 곳만 맞고 나머지가 옛 문구로 남는 일이 없도록 전 오퍼레이션을 훑는다.
        List<String> drifted = new ArrayList<>();
        eachOperation(spec, (where, operation) -> operation.path("responses").properties().forEach(r -> {
            if ("409".equals(r.getKey())) {
                return;   // 409는 엔드포인트별 사유다 — 위 전용 테스트가 본다.
            }
            String expected = CANONICAL.get(r.getKey());
            if (expected != null && !expected.equals(r.getValue().path("description").asText())) {
                drifted.add(where + " " + r.getKey());
            }
        }));
        assertThat(drifted).as("문구가 어긋난 응답").isEmpty();
    }

    /**
     * 409를 내는 엔드포인트 전부. ConflictException(13곳)과 MoveImpactException(1곳)을
     * 엔드포인트로 접은 결과다 — 사이트가 늘면 아래 SOURCE 카운트 가드가 먼저 깨져
     * 여기 매핑을 다시 보게 만든다.
     */
    private static final List<String> CONFLICT_ENDPOINTS = List.of(
            "POST /api/wiki/pages",
            "PUT /api/wiki/pages/{id}",
            "PUT /api/wiki/pages/{id}/collaboration-draft",
            "POST /api/wiki/pages/{id}/move",
            "DELETE /api/wiki/pages/{id}",
            "POST /api/wiki/pages/{id}/archive",
            "POST /api/wiki/pages/{id}/unarchive",
            "PUT /api/wiki/pages/{pageId}/tasks/{lineNo}",
            "POST /api/wiki/spaces/{spaceId}/templates",
            "PUT /api/wiki/templates/{templateId}",
            "POST /api/wiki/pages/{pageId}/save-as-template");

    @Test
    void 실제로_409를_내는_엔드포인트만_409를_문서화한다() throws Exception {
        List<String> documented = new ArrayList<>();
        eachOperation(spec(), (where, operation) -> {
            if (!operation.path("responses").path("409").isMissingNode()) {
                documented.add(where);
            }
        });
        // 양방향으로 본다 — 빠진 것과 남는 것을 같은 실패로 잡는다.
        assertThat(documented).containsExactlyInAnyOrderElementsOf(CONFLICT_ENDPOINTS);
    }

    @Test
    void 문서화한_409에는_사유가_적혀_있다() throws Exception {
        List<String> blank = new ArrayList<>();
        eachOperation(spec(), (where, operation) -> {
            JsonNode conflict = operation.path("responses").path("409");
            if (!conflict.isMissingNode() && conflict.path("description").asText().isBlank()) {
                blank.add(where);
            }
        });
        assertThat(blank).as("사유 없는 409").isEmpty();

        // 낙관적 락 자리는 정본 문구를 그대로 쓴다(세 서비스 공통).
        assertThat(spec().at("/paths/~1api~1wiki~1pages~1{id}/put/responses/409/description").asText())
                .isEqualTo("버전 충돌 — expectedVersion 불일치");
        // 엔드포인트별 사유는 정본 문구가 아니라 그 엔드포인트의 사정을 적는다.
        assertThat(spec().at("/paths/~1api~1wiki~1pages~1{id}~1archive/post/responses/409/description")
                .asText()).isEqualTo("이미 보관된 문서입니다");
    }

    /**
     * 409를 던지는 자리가 늘었는데 문서를 안 고치는 경우를 잡는다. 스펙만 봐서는 알 수 없어
     * 소스를 센다 — 숫자가 바뀌면 새 사이트를 어느 엔드포인트에 접을지 정하고 위 목록을 고치라는 뜻이다.
     */
    /** 이동 영향 409만 바디가 다르다 — 나머지는 전부 PlatformError여야 한다. */
    @Test
    void 이동_영향_409만_다른_스키마를_쓴다() throws Exception {
        JsonNode spec = spec();
        assertThat(spec.at("/paths/~1api~1wiki~1pages~1{id}~1move/post/responses/409"
                + "/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/MoveImpactError");
        assertThat(spec.at("/components/schemas/MoveImpactError/properties/impact/properties"
                + "/newlyRestrictedBy/items/$ref").asText())
                .isEqualTo("#/components/schemas/InheritedRestriction");
        // $ref가 가리키는 스키마가 실제로 문서에 있어야 한다(끊긴 참조 방지).
        assertThat(spec.at("/components/schemas/InheritedRestriction").isMissingNode()).isFalse();

        List<String> others = new ArrayList<>();
        eachOperation(spec, (where, operation) -> {
            JsonNode ref = operation.at("/responses/409/content/application~1json/schema/$ref");
            if (!ref.isMissingNode() && !"POST /api/wiki/pages/{id}/move".equals(where)
                    && !ERROR_REF.equals(ref.asText())) {
                others.add(where + " " + ref.asText());
            }
        });
        assertThat(others).as("PlatformError가 아닌 409").isEmpty();
    }

    @Test
    void 인라인_첨부는_415를_문서화한다() throws Exception {
        JsonNode inline = spec().at("/paths/~1api~1wiki~1attachments~1{id}~1inline/get/responses");
        assertThat(inline.at("/415/description").asText()).isEqualTo("허용되지 않는 미디어 타입");
        assertThat(inline.at("/415/content/application~1json/schema/$ref").asText()).isEqualTo(ERROR_REF);
        // @ApiResponse를 더 달아도 성공 응답이 사라지지 않았는지 같이 본다.
        assertThat(inline.at("/200/content/application~1octet-stream").isMissingNode()).isFalse();
    }

    @Test
    void 충돌을_던지는_자리_수가_고정되어_있다() throws Exception {
        assertThat(countInMainSources("new ConflictException(", "")).isEqualTo(13);
        // 던지는 쪽이 FQN을 쓴다(new com.platform...MoveImpactException) — 이름 형태에 기대지 않고
        // 클래스명으로 센다. 선언 파일 자체(생성자)는 빼야 실제 throw 자리만 남는다.
        assertThat(countInMainSources("MoveImpactException(", "MoveImpactException.java")).isEqualTo(1);
    }

    private static long countInMainSources(String needle, String excludedFile) throws Exception {
        try (var paths = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> excludedFile.isEmpty() || !p.getFileName().toString().equals(excludedFile))
                    .mapToLong(p -> {
                        try {
                            String body = java.nio.file.Files.readString(p);
                            long n = 0;
                            for (int i = body.indexOf(needle); i >= 0; i = body.indexOf(needle, i + 1)) {
                                n++;
                            }
                            return n;
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).sum();
        }
    }

    @Test
    void 서비스_메타가_스펙에_담긴다() throws Exception {
        JsonNode spec = spec();
        assertThat(spec.at("/info/title").asText()).isEqualTo("WIKI API");
        assertThat(spec.at("/info/version").asText()).isNotBlank();
        assertThat(spec.at("/servers/0/url").asText()).isEqualTo("/");
    }
}
