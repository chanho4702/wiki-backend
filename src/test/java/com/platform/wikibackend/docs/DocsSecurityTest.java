package com.platform.wikibackend.docs;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.config.DocsPrincipalFilter;
import com.platform.wikibackend.config.PublicReadPermissionClient;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.repository.NotificationPrefRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 문서 인스턴스(docs) 프로필의 경계 검증.
 *
 * 이 테스트가 지키는 것은 하나다 — **웹에서는 아무도 쓰지 못한다**. 읽기가 열려 있다는 것보다
 * 쓰기가 닫혀 있다는 쪽이 회귀했을 때 위험하므로, 거부 케이스를 메서드별로 나눠 둔다.
 *
 * test 소스셋의 Fake*(@Primary) 빈들은 `@Profile("!docs")`로 이 프로필에서 빠진다 — 여기서
 * 실제로 도는 것이 {@link PublicReadPermissionClient}임을 첫 테스트가 직접 확인한다.
 */
@SpringBootTest
@ActiveProfiles({"test", "docs"})
@TestPropertySource(properties = "platform.docs.import-token=test-token")
class DocsSecurityTest {

    private static final String TOKEN_HEADER = DocsPrincipalFilter.IMPORT_TOKEN_HEADER;
    private static final String SPACE_BODY = "{\"key\":\"docs\",\"name\":\"MSA_TEMPLATE 정리\",\"description\":null}";

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired NotificationPrefRepository notificationPrefs;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionClient permissions;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        restrictions.deleteAll();
        notificationPrefs.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
    }

    @Test
    void docs_프로필은_공개_읽기_권한_클라이언트를_쓴다() {
        assertThat(permissions).isInstanceOf(PublicReadPermissionClient.class);
    }

    // ── 읽기: 로그인 없이 열린다 ──

    @Test
    void 익명_GET_스페이스_목록은_200이고_전부_보인다() throws Exception {
        spaces.save(Space.of("docs", "정리", null, 1L));

        mvc.perform(get("/api/wiki/spaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value("docs"));
    }

    @Test
    void 익명_GET_페이지_본문도_200이다() throws Exception {
        Space space = spaces.save(Space.of("docs", "정리", null, 1L));
        Page page = pages.save(Page.of(space.getId(), null, "00 시작", "본문", 1L));

        mvc.perform(get("/api/wiki/pages/" + page.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("00 시작"));
    }

    @Test
    void 익명_GraphQL_검색은_인증에_막히지_않는다() throws Exception {
        String body = """
                {"query":"query Search($input: SearchInput!) { search(input: $input) { total } }",
                 "operationName":"Search","variables":{"input":{"query":"배포"}}}
                """;

        mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.total").value(0));
    }

    // ── 쓰기: 웹에서는 전부 막힌다 ──

    @Test
    void 익명_POST_스페이스는_403이고_플랫폼_오류_계약을_지킨다() throws Exception {
        mvc.perform(post("/api/wiki/spaces").contentType(MediaType.APPLICATION_JSON).content(SPACE_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("읽기 전용 문서 인스턴스입니다."));

        assertThat(spaces.count()).isZero();
    }

    @Test
    void 익명_PUT과_DELETE도_403이다() throws Exception {
        Space space = spaces.save(Space.of("docs", "정리", null, 1L));

        mvc.perform(put("/api/wiki/spaces/" + space.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"바뀐 이름\",\"description\":null}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/wiki/spaces/" + space.getId()))
                .andExpect(status().isForbidden());

        assertThat(spaces.findById(space.getId()).orElseThrow().getName()).isEqualTo("정리");
    }

    /** 공개 인스턴스는 조회수를 세지 않는다 — 프론트가 부르지 않을뿐더러 서버도 받지 않는다. */
    @Test
    void 조회수_기록은_403이다() throws Exception {
        Space space = spaces.save(Space.of("docs", "정리", null, 1L));
        Page page = pages.save(Page.of(space.getId(), null, "00 시작", "본문", 1L));

        mvc.perform(post("/api/wiki/pages/" + page.getId() + "/views"))
                .andExpect(status().isForbidden());
    }

    /** 조회수는 임포터에게도 열지 않는다 — 임포트가 조회수를 부풀리면 안 된다. */
    @Test
    void 조회수_기록은_올바른_토큰으로도_403이다() throws Exception {
        Space space = spaces.save(Space.of("docs", "정리", null, 1L));
        Page page = pages.save(Page.of(space.getId(), null, "00 시작", "본문", 1L));

        mvc.perform(post("/api/wiki/pages/" + page.getId() + "/views").header(TOKEN_HEADER, "test-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 틀린_임포트_토큰은_403이다() throws Exception {
        mvc.perform(post("/api/wiki/spaces").header(TOKEN_HEADER, "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(SPACE_BODY))
                .andExpect(status().isForbidden());

        assertThat(spaces.count()).isZero();
    }

    // ── 임포터: 루프백 + 서버 비밀 토큰으로만 쓴다 ──

    @Test
    void 올바른_토큰이면_스페이스를_만들고_작성자는_임포터다() throws Exception {
        mvc.perform(post("/api/wiki/spaces").header(TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(SPACE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("docs"));

        List<Space> saved = spaces.findAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getCreatedBy()).isEqualTo(DocsPrincipalFilter.IMPORTER_USER_ID);
    }

    @Test
    void 올바른_토큰이면_페이지도_만든다() throws Exception {
        Space space = spaces.save(Space.of("docs", "정리", null, 1L));

        mvc.perform(post("/api/wiki/pages").header(TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId()
                                + ",\"parentId\":null,\"title\":\"00 시작\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated());

        assertThat(pages.findBySpaceIdOrderById(space.getId())).hasSize(1);
    }

    // ── 사용자 범위 경로: GET이라고 안전하지 않다 ──

    /**
     * "GET은 읽기"라는 가정을 깨는 경로들. 특히 `/notifications/prefs`는 행이 없으면 기본 설정을
     * INSERT 하므로, 열어 두면 익명 GET 하나가 user_id=0 행을 만든다.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/wiki/notifications",
            "/api/wiki/notifications/prefs",
            "/api/wiki/stars",
            "/api/wiki/recent",
            "/api/wiki/tasks/mine",
            "/api/wiki/audit/space-deletions"})
    void 사용자_범위_경로는_익명_GET도_403이다(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isForbidden());
    }

    /** 임포터에게도 열 이유가 없다 — 임포터는 문서만 넣는다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/wiki/notifications/prefs",
            "/api/wiki/stars",
            "/api/wiki/recent",
            "/api/wiki/tasks/mine"})
    void 사용자_범위_경로는_임포터_토큰으로도_403이다(String path) throws Exception {
        mvc.perform(get(path).header(TOKEN_HEADER, "test-token")).andExpect(status().isForbidden());
    }

    /**
     * 내부 이관 API(W29 X1)는 공개 문서 인스턴스에 존재하지 않는다. 여기에는 org 원장도 이관
     * 원장도 없어 "옮겨 넣을 수 있는 상태"가 아니고, 열려 있으면 공개 인스턴스가 쓰기 창구가 된다.
     */
    @Test
    void 내부_이관_API는_docs에서_전부_403이다() throws Exception {
        mvc.perform(get("/internal/wiki/import/spaces/1")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/wiki/import/spaces/1")
                        .header(TOKEN_HEADER, "test-token")).andExpect(status().isForbidden());
        mvc.perform(post("/internal/wiki/import/pages")
                        .header(TOKEN_HEADER, "test-token")
                        .header("X-Internal-Token", "test-token")
                        .header("X-Actor-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    /** 위 403이 "필터 체인이 막았다"인지 확인한다 — 서비스까지 갔다면 기본 설정 행이 남는다. */
    @Test
    void 알림_설정_익명_GET은_기본_설정_행을_만들지_않는다() throws Exception {
        mvc.perform(get("/api/wiki/notifications/prefs")).andExpect(status().isForbidden());

        assertThat(notificationPrefs.count()).isZero();
    }

    // ── 페이지 제한: 익명(user 0)은 어떤 주체에도 해당하지 않는다 ──

    /**
     * 임포터가 실수로 VIEW 제한을 남긴 문서가 공개되면 안 된다. docs의 PrincipalDirectory는
     * 항상 통과라 제한 저장 자체는 성립한다 — 그래서 판정 쪽이 실제로 닫히는지 확인한다.
     * 익명 주체는 sub=0이고 TeamDirectory도 빈 목록이라 USER·TEAM 어디에도 걸리지 않는다.
     */
    @Test
    void VIEW_제한이_걸린_페이지는_익명에게_403이다() throws Exception {
        Space space = spaces.save(Space.of("docs", "정리", null, 1L));
        Page page = pages.save(Page.of(space.getId(), null, "비공개 메모", "본문", 1L));

        mvc.perform(put("/api/wiki/pages/" + page.getId() + "/restrictions")
                        .header(TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":[{\"type\":\"USER\",\"id\":1}],\"edit\":[]}"))
                .andExpect(status().isOk());
        assertThat(restrictions.count()).isPositive();   // 저장이 실제로 됐다

        mvc.perform(get("/api/wiki/pages/" + page.getId()))
                .andExpect(status().isForbidden());
    }
}
