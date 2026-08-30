package com.platform.wikibackend.search;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageLabel;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageLabelRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 라이트 검색 — OpenSearch 없는 배포의 `/graphql`.
 *
 * 질의는 wiki-front의 WikiSearch와 **같은 필드 목록**을 쓴다. 여기서만 필드를 줄여 쓰면 계약
 * 구멍이 테스트를 통과한다 — search-service에서 pageType이 그렇게 빠져 있었다.
 */
@SpringBootTest
@ActiveProfiles("test")
class LiteSearchTest {

    private static final long USER = 7L;
    private static final long OTHER = 8L;

    private static final String SEARCH = """
            query Search($input: SearchInput!) {
              search(input: $input) {
                total
                totalExact
                tookMs
                hits {
                  id
                  docType
                  spaceId
                  spaceKey
                  spaceName
                  pageId
                  pageType
                  title
                  filename
                  highlights
                  updatedAt
                  score
                }
              }
            }
            """;

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageLabelRepository labels;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient permissions;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    MockMvc mvc;
    Space space;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        restrictions.deleteAll();
        labels.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        permissions.reset();

        space = spaces.save(Space.of("ENG", "개발 위키", "설명", USER));
        permissions.allow(USER, space.getId(), WikiAction.VIEW);
    }

    @Test
    void 제목과_본문에서_찾고_제목_일치를_위로_올린다() throws Exception {
        page("배포 절차", "본문은 다른 이야기");
        page("다른 문서", "여기에 배포 절차가 적혀 있다");

        search(USER, input("배포"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.total").value(2))
                .andExpect(jsonPath("$.data.search.totalExact").value(true))
                .andExpect(jsonPath("$.data.search.hits[0].title").value("배포 절차"));
    }

    /** 형태소 분석이 없어 부분 문자열로 찾는다 — 조사가 붙은 낱말도 걸려야 쓸모가 있다. */
    @Test
    void 조사가_붙어도_부분_일치로_찾는다() throws Exception {
        page("환경 설정", "설정을 먼저 끝낸다");

        search(USER, input("설정"))
                .andExpect(jsonPath("$.data.search.total").value(1));
    }

    @Test
    void 폴더와_문서를_구분해서_내려준다() throws Exception {
        pages.save(Page.of(space.getId(), null, "배포 폴더", "", USER,
                PageType.FOLDER, com.platform.wikibackend.domain.PageStatus.PUBLISHED));

        search(USER, input("배포"))
                .andExpect(jsonPath("$.data.search.hits[0].pageType").value("FOLDER"));
    }

    /** 블로그 글(W24)은 BLOG로 내려간다 — enum에 없으면 글 하나가 검색 전체를 오류로 만든다. */
    @Test
    void 블로그_글은_BLOG_타입으로_내려준다() throws Exception {
        pages.save(Page.of(space.getId(), null, "주간 소식", "이번 주 배포 정리", USER,
                PageType.BLOG, com.platform.wikibackend.domain.PageStatus.PUBLISHED));

        search(USER, input("소식"))
                .andExpect(jsonPath("$.data.search.hits[0].pageType").value("BLOG"));
    }

    /** 접근할 수 없는 스페이스는 요청해도 결과가 되지 않는다 — 여기로 권한을 넓힐 수 없다. */
    @Test
    void 볼_수_없는_스페이스는_결과에_없다() throws Exception {
        page("배포 절차", "본문");

        search(OTHER, input("배포"))
                .andExpect(jsonPath("$.data.search.total").value(0));
    }

    /** 페이지 단위 제한(W18)은 SQL로 판정할 수 없어 후필터가 맡는다 — 새면 제목이 그대로 샌다. */
    @Test
    void 페이지_제한이_걸린_문서는_결과에서_빠진다() throws Exception {
        Page open = page("배포 절차", "본문");
        Page closed = page("배포 비밀", "본문");
        permissions.allow(OTHER, space.getId(), WikiAction.VIEW);
        restrictions.save(com.platform.wikibackend.domain.PageRestriction.of(
                closed.getId(),
                com.platform.wikibackend.domain.PageRestriction.Type.VIEW,
                com.platform.wikibackend.domain.PageRestriction.PrincipalType.USER,
                USER, USER));

        search(OTHER, input("배포"))
                .andExpect(jsonPath("$.data.search.total").value(1))
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(open.getId())));
    }

    @Test
    void 라벨로_거른다() throws Exception {
        Page tagged = page("배포 절차", "본문");
        page("배포 회고", "본문");
        labels.save(PageLabel.of(tagged.getId(), "wave-d", USER));

        Map<String, Object> filtered = input("배포");
        filtered.put("labels", List.of("Wave D")); // 저장할 때와 같은 규칙으로 정규화된다

        search(USER, filtered)
                .andExpect(jsonPath("$.data.search.total").value(1))
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(tagged.getId())));
    }

    /**
     * 정렬은 search-service와 **같은 값으로 같은 순서**를 내야 한다 — 배포에 따라 "최신순"이
     * 다르게 나오면 사용자는 어느 쪽이 맞는지 알 수 없다.
     */
    @Test
    void 수정일_순으로_정렬한다() throws Exception {
        Page older = page("배포 절차", "본문");
        Page newer = page("배포 회고", "본문");
        // 저장 시각에 기대지 않는다 — 연달아 저장하면 같은 시각이 찍혀 순서가 tie-break에 달린다.
        touch(older, "2026-08-01T00:00:00Z");
        touch(newer, "2026-08-09T00:00:00Z");

        Map<String, Object> desc = input("배포");
        desc.put("sort", "UPDATED_DESC");
        search(USER, desc)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(newer.getId())));

        Map<String, Object> asc = input("배포");
        asc.put("sort", "UPDATED_ASC");
        search(USER, asc)
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(older.getId())));
    }

    @Test
    void 잘못된_기간_형식은_거부한다() throws Exception {
        page("배포 절차", "본문");
        Map<String, Object> bad = input("배포");
        bad.put("updatedAfter", "어제");

        // 프론트는 extensions로 400·429·503을 갈라 다른 복구 안내를 그린다 — search-service와 같은 모양이어야 한다.
        search(USER, bad)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].extensions.httpStatus").value(400))
                .andExpect(jsonPath("$.errors[0].extensions.code").value("BAD_REQUEST"));
    }

    /** 본문의 마크업이 하이라이트를 통해 그대로 나가면 검색 결과가 주입 통로가 된다. */
    @Test
    void 하이라이트는_본문의_마크업을_이스케이프한다() throws Exception {
        page("주의", "<script>alert(1)</script> 배포 직전 확인");

        search(USER, input("배포"))
                .andExpect(jsonPath("$.data.search.hits[0].highlights[0]").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("<em>배포</em>"),
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>")))));
    }

    @Test
    void 빈_검색어는_질의하지_않고_빈_결과다() throws Exception {
        page("배포 절차", "본문");

        search(USER, input("   "))
                .andExpect(jsonPath("$.data.search.total").value(0));
    }

    /** updatedAt을 못 박는다 — 도메인에는 시각을 지정해 저장하는 길이 없다(있어서도 안 된다). */
    private void touch(Page page, String instant) {
        jdbc.update("update page set updated_at = ? where id = ?",
                java.sql.Timestamp.from(java.time.Instant.parse(instant)), page.getId());
    }

    private Page page(String title, String content) {
        return pages.save(Page.of(space.getId(), null, title, content, USER));
    }

    private static Map<String, Object> input(String query) {
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("query", query);
        return input;
    }

    private ResultActions search(long userId, Map<String, Object> input) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "operationName", "Search",
                "query", SEARCH,
                "variables", Map.of("input", input)));
        return mvc.perform(post("/graphql")
                .with(asUser(userId, "u" + userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
