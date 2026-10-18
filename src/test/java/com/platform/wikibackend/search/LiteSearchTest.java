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
    @Autowired com.platform.wikibackend.repository.AttachmentRepository attachments;
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
        jdbc.update("delete from attachment");
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

    // ── 예전 후보 창(500건) 초과 회귀 — 정렬은 SQL에서 자르기 전에 적용돼야 한다(2026-09-13) ──
    // 지금은 창 없이 SQL이 권한까지 적용해 세므로 total이 정확하다(totalExact=true).

    /** 예전 후보 창(500)보다 많은 수 — 창이 다시 생기면 이 테스트들이 잡는다. */
    private static final int WIDE = 505;

    /** WIDE건을 만든다: 전부 제목 일치(관련도 3), updatedAt은 2026-01-01부터 1분 간격. */
    private void fillWindow(String titlePrefix) {
        for (int i = 0; i < WIDE; i++) {
            touch(page(titlePrefix + " " + i, "본문"), java.time.Instant.parse("2026-01-01T00:00:00Z")
                    .plusSeconds(60L * i).toString());
        }
    }

    private static Map<String, Object> sorted(String query, String sort) {
        Map<String, Object> input = input(query);
        input.put("sort", sort);
        input.put("size", 1);
        return input;
    }

    @Test
    void 후보창_초과에서_오래된순은_창_밖의_진짜_오래된_문서를_준다() throws Exception {
        fillWindow("창고 문서");
        Page oldest = page("창고 문서 최초", "본문");
        touch(oldest, "2020-01-01T00:00:00Z");

        search(USER, sorted("창고", "UPDATED_ASC"))
                .andExpect(jsonPath("$.data.search.total").value(WIDE + 1))
                .andExpect(jsonPath("$.data.search.totalExact").value(true))
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(oldest.getId())));
    }

    @Test
    void 후보창_초과에서_최신순은_관련도_낮은_최신_문서를_놓치지_않는다() throws Exception {
        fillWindow("창고 문서");
        // 제목에는 검색어가 없고 본문에만 있다(관련도 1) — 관련도순으로 자르면 창 밖으로 밀리는 문서.
        Page newest = page("전혀 다른 제목", "창고 관련 최신 메모");
        touch(newest, "2027-01-01T00:00:00Z");

        search(USER, sorted("창고", "UPDATED_DESC"))
                .andExpect(jsonPath("$.data.search.total").value(WIDE + 1))
                .andExpect(jsonPath("$.data.search.totalExact").value(true))
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(newest.getId())));
    }

    @Test
    void 후보창_초과에서도_관련도순은_제목_일치를_먼저_준다() throws Exception {
        // 본문 일치(관련도 1)가 창보다 많고 전부 더 최신이어도, 오래된 제목 일치(관련도 3)가 첫 결과다.
        for (int i = 0; i < WIDE; i++) {
            touch(page("메모 " + i, "창고 이야기"), java.time.Instant.parse("2026-01-01T00:00:00Z")
                    .plusSeconds(60L * i).toString());
        }
        Page titled = page("창고 안내", "본문");
        touch(titled, "2020-01-01T00:00:00Z");

        search(USER, sorted("창고", "RELEVANCE"))
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(titled.getId())))
                .andExpect(jsonPath("$.data.search.hits[0].score").value(3));
    }

    // ── 권한 후필터 누락 회귀(2026-10-16) — 앞쪽 후보가 전부 제한 문서여도 뒤의 볼 수 있는 문서가 나와야 한다 ──

    /** OTHER가 볼 수 없도록 USER 전용 VIEW 제한을 건다. */
    private void restrictToUser(Page page) {
        restrictions.save(com.platform.wikibackend.domain.PageRestriction.of(
                page.getId(),
                com.platform.wikibackend.domain.PageRestriction.Type.VIEW,
                com.platform.wikibackend.domain.PageRestriction.PrincipalType.USER,
                USER, USER));
    }

    @Test
    void 앞쪽_후보가_전부_제한_문서여도_뒤의_볼_수_있는_문서를_찾는다() throws Exception {
        permissions.allow(OTHER, space.getId(), WikiAction.VIEW);
        // 제목 일치(관련도 3) + 더 최신인 제한 문서가 WIDE건 — 어떤 정렬로도 볼 수 있는 문서보다 앞이다.
        Page parent = page("기밀 상위", "본문");
        restrictToUser(parent);
        for (int i = 0; i < WIDE; i++) {
            Page child = pages.save(Page.of(space.getId(), parent.getId(), "보고서 기밀 " + i, "본문", USER));
            touch(child, java.time.Instant.parse("2026-06-01T00:00:00Z").plusSeconds(60L * i).toString());
        }
        Page visible = page("공개 메모", "보고서 요약"); // 본문 일치(관련도 1), 가장 오래됨
        touch(visible, "2020-01-01T00:00:00Z");

        for (String sort : List.of("RELEVANCE", "UPDATED_DESC", "UPDATED_ASC")) {
            Map<String, Object> in = input("보고서");
            in.put("sort", sort);
            search(OTHER, in)
                    .andExpect(jsonPath("$.data.search.total").value(1))
                    .andExpect(jsonPath("$.data.search.totalExact").value(true))
                    .andExpect(jsonPath("$.data.search.hits.length()").value(1))
                    .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(visible.getId())));
        }
        // 제한 대상자 본인에게는 상속된 제한 문서가 그대로 다 보인다 — 거르기가 과하면 안 된다.
        search(USER, input("보고서"))
                .andExpect(jsonPath("$.data.search.total").value(WIDE + 1));
    }

    @Test
    void 제한_문서_사이의_결과를_페이지로_끝까지_넘길_수_있다() throws Exception {
        permissions.allow(OTHER, space.getId(), WikiAction.VIEW);
        List<Long> expected = new java.util.ArrayList<>();
        // 제한 문서와 볼 수 있는 문서를 섞어 최신순으로 늘어놓는다: 볼 수 있는 것 25건이 제한 문서 사이사이에 있다.
        for (int i = 0; i < WIDE + 25; i++) {
            Page p = page("분기 보고서 " + i, "본문");
            touch(p, java.time.Instant.parse("2026-01-01T00:00:00Z").plusSeconds(60L * i).toString());
            if (i % 21 == 0 && expected.size() < 25) expected.add(0, p.getId()); // 최신순이므로 앞에 넣는다
            else restrictToUser(p);
        }
        int total = expected.size();

        List<String> seen = new java.util.ArrayList<>();
        for (int pageNo = 0; pageNo * 10 < total; pageNo++) {
            Map<String, Object> in = input("보고서");
            in.put("sort", "UPDATED_DESC");
            in.put("size", 10);
            in.put("page", pageNo);
            String body = search(OTHER, in)
                    .andExpect(jsonPath("$.data.search.total").value(total))
                    .andExpect(jsonPath("$.data.search.totalExact").value(true))
                    .andReturn().getResponse().getContentAsString();
            com.fasterxml.jackson.databind.JsonNode hits = json.readTree(body).at("/data/search/hits");
            hits.forEach(h -> seen.add(h.get("id").asText()));
        }
        // 겹침·빠짐 없이 요청 정렬 그대로 전부 — 마지막 쪽 뒤는 비어 있다.
        org.assertj.core.api.Assertions.assertThat(seen)
                .containsExactlyElementsOf(expected.stream().map(String::valueOf).toList());
        Map<String, Object> beyond = input("보고서");
        beyond.put("sort", "UPDATED_DESC");
        beyond.put("size", 10);
        beyond.put("page", 3);
        search(OTHER, beyond).andExpect(jsonPath("$.data.search.hits.length()").value(0));
    }

    @Test
    void 제한된_페이지의_첨부는_빠지고_페이지와_첨부가_한_목록으로_정렬된다() throws Exception {
        permissions.allow(OTHER, space.getId(), WikiAction.VIEW);
        Page open = page("설계 개요", "본문");
        Page closed = page("설계 기밀", "본문");
        restrictToUser(closed);
        com.platform.wikibackend.domain.Attachment openFile = attachments.save(
                com.platform.wikibackend.domain.Attachment.of(open.getId(), "설계도.pdf", "application/pdf", 10L, "k1", USER));
        attachments.save(
                com.platform.wikibackend.domain.Attachment.of(closed.getId(), "설계-비밀.pdf", "application/pdf", 10L, "k2", USER));
        touch(open, "2026-01-01T00:00:00Z");
        jdbc.update("update attachment set created_at = ? where id = ?",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-02-01T00:00:00Z")), openFile.getId());

        Map<String, Object> in = input("설계");
        in.put("sort", "UPDATED_DESC");
        search(OTHER, in)
                .andExpect(jsonPath("$.data.search.total").value(2))
                .andExpect(jsonPath("$.data.search.hits[0].docType").value("ATTACHMENT"))
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(openFile.getId())))
                .andExpect(jsonPath("$.data.search.hits[0].pageId").value(String.valueOf(open.getId())))
                .andExpect(jsonPath("$.data.search.hits[1].id").value(String.valueOf(open.getId())));
    }

    /**
     * 부모가 다른 스페이스에 있는 페이지(부모를 옮긴 뒤 휴지통에서 하위를 되살린 경우) — 그 부모의 VIEW 제한도
     * 상속된다. 스페이스 단위 숨김 계산이 스페이스 밖 조상을 못 보면 본문은 막아도 total이 존재를 흘린다.
     */
    @Test
    void 다른_스페이스의_조상_제한도_건수와_결과에서_숨긴다() throws Exception {
        Space other = spaces.save(Space.of("OPS", "운영", null, USER));
        Page foreignParent = pages.save(Page.of(other.getId(), null, "운영 기밀 상위", "본문", USER));
        restrictToUser(foreignParent);
        Page child = pages.save(Page.of(space.getId(), foreignParent.getId(), "분기 감사 결과", "본문", USER));
        permissions.allow(OTHER, space.getId(), WikiAction.VIEW);

        search(OTHER, input("감사"))
                .andExpect(jsonPath("$.data.search.total").value(0))
                .andExpect(jsonPath("$.data.search.hits.length()").value(0));
        // 제한 대상자 본인에게는 보인다
        permissions.allow(USER, space.getId(), WikiAction.VIEW);
        search(USER, input("감사"))
                .andExpect(jsonPath("$.data.search.total").value(1))
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(child.getId())));
    }

    @Test
    void 제한을_풀면_다음_검색에_바로_나온다() throws Exception {
        permissions.allow(OTHER, space.getId(), WikiAction.VIEW);
        Page closed = page("배포 기밀", "본문");
        restrictToUser(closed);
        search(OTHER, input("배포")).andExpect(jsonPath("$.data.search.total").value(0));

        restrictions.deleteAll(); // 캐시가 있으면 여기서 낡은 판정이 남는다
        search(OTHER, input("배포"))
                .andExpect(jsonPath("$.data.search.total").value(1))
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(closed.getId())));
    }

    @Test
    void 숨길_문서가_아주_많아도_메모리_거르기로_같은_결과를_낸다() throws Exception {
        permissions.allow(OTHER, space.getId(), WikiAction.VIEW);
        Page parent = page("기밀 묶음", "본문");
        restrictToUser(parent);
        // 상속으로 숨는 문서가 SQL 인라인 상한을 넘는다 → 키 스캔 경로.
        List<Page> hidden = new java.util.ArrayList<>();
        for (int i = 0; i < LiteSearchService.HIDDEN_INLINE_LIMIT + 5; i++) {
            hidden.add(Page.of(space.getId(), parent.getId(), "점검표 " + i, "본문", USER));
        }
        pages.saveAll(hidden);
        Page a = page("공개 점검표 가", "본문");
        Page b = page("공개 점검표 나", "본문");
        touch(a, "2020-01-01T00:00:00Z");
        touch(b, "2020-01-02T00:00:00Z");

        Map<String, Object> in = input("점검표");
        in.put("sort", "UPDATED_ASC");
        search(OTHER, in)
                .andExpect(jsonPath("$.data.search.total").value(2))
                .andExpect(jsonPath("$.data.search.totalExact").value(true))
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(a.getId())))
                .andExpect(jsonPath("$.data.search.hits[1].id").value(String.valueOf(b.getId())));
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

    // ── W-20: LIKE 와일드카드는 글자 그대로 찾는다 ──

    /** "50%"·"snake_case"의 %·_가 와일드카드로 해석되면 엉뚱한 문서가 걸리고, "%" 한 글자는 전 문서를 훑는다. */
    @Test
    void 퍼센트와_밑줄은_글자_그대로_찾는다() throws Exception {
        Page percent = page("성장률 50% 달성", "본문");
        page("성장률 500 달성", "본문");
        Page underscore = page("snake_case 규칙", "본문");
        page("snakeXcase 규칙", "본문");

        search(USER, input("50%"))
                .andExpect(jsonPath("$.data.search.total").value(1))
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(percent.getId())));
        search(USER, input("snake_case"))
                .andExpect(jsonPath("$.data.search.total").value(1))
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(underscore.getId())));
        search(USER, input("%"))
                .andExpect(jsonPath("$.data.search.total").value(1));
    }

    @Test
    void 제목_필터도_와일드카드를_글자로_본다() throws Exception {
        page("snake_case 규칙", "본문");
        page("snakeXcase 규칙", "본문");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/wiki/spaces/{id}/pages/search", space.getId()).param("q", "snake_")
                        .with(asUser(USER, "u")))
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── 문서 종류 필터(pageTypes, 프론트 계약 2026-10-18) ──

    @Test
    void pageTypes로_문서_종류를_거른다() throws Exception {
        pages.save(Page.of(space.getId(), null, "종류검색 문서", "본문", USER));
        Page blog = pages.save(Page.of(space.getId(), null, "종류검색 블로그", "본문", USER,
                com.platform.wikibackend.domain.PageType.BLOG, com.platform.wikibackend.domain.PageStatus.PUBLISHED));

        Map<String, Object> in = input("종류검색");
        in.put("pageTypes", List.of("BLOG"));
        search(USER, in)
                .andExpect(jsonPath("$.data.search.total").value(1))
                .andExpect(jsonPath("$.data.search.hits[0].id").value(String.valueOf(blog.getId())));
        in.put("pageTypes", List.of("PAGE", "BLOG"));
        search(USER, in).andExpect(jsonPath("$.data.search.total").value(2));
        search(USER, input("종류검색")).andExpect(jsonPath("$.data.search.total").value(2));
    }
}
