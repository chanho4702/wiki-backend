package com.platform.wikibackend.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 라이트 검색의 경계(2026-10-16 리뷰 보강).
 *
 * 상한을 작게(숨김 인라인 2·스캔 5) 줄여 메모리 거르기 경로의 절단·하한 total을 실제로 태우고,
 * 같은 시각의 페이지·첨부를 여러 쪽에 걸쳐 넘겨 SQL 정렬과 병합 비교자가 어긋나지 않는지 본다.
 * 권한 스냅샷이 낡은 경우(요청 도중 제한이 걸림)는 숨김 집합을 일부러 비워 재현한다.
 */
@SpringBootTest(properties = {
        "platform.wiki.search.lite.hidden-inline-limit=2",
        "platform.wiki.search.lite.scan-limit=5",
})
@ActiveProfiles("test")
class LiteSearchBoundaryTest {

    private static final long USER = 7L;
    private static final long OTHER = 8L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SEARCH = """
            query Search($input: SearchInput!) {
              search(input: $input) { total totalExact hits { id docType pageId } }
            }
            """;

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired AttachmentRepository attachments;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient permissions;
    @MockitoSpyBean EffectivePermissionService effective;

    MockMvc mvc;
    Space space;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(effective);
        restrictions.deleteAll();
        jdbc.update("delete from attachment");
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        permissions.reset();
        space = spaces.save(Space.of("ENG", "개발", null, USER));
        permissions.allow(USER, space.getId(), WikiAction.VIEW);
        permissions.allow(OTHER, space.getId(), WikiAction.VIEW);
    }

    private Page page(String title) {
        return pages.save(Page.of(space.getId(), null, title, "본문", USER));
    }

    private void at(String table, long id, Instant when) {
        String column = table.equals("attachment") ? "created_at" : "updated_at";
        jdbc.update("update " + table + " set " + column + " = ? where id = ?", Timestamp.from(when), id);
    }

    private void restrict(Page p) {
        restrictions.save(PageRestriction.of(p.getId(), PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, USER, USER));
    }

    private JsonNode search(long user, Map<String, Object> input) throws Exception {
        String body = JSON.writeValueAsString(Map.of("operationName", "Search", "query", SEARCH,
                "variables", Map.of("input", input)));
        String res = mvc.perform(post("/graphql").with(asUser(user, "u" + user))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(res).at("/data/search");
    }

    private static Map<String, Object> input(String q, String sort, int size, int page) {
        Map<String, Object> in = new java.util.HashMap<>();
        in.put("query", q);
        in.put("sort", sort);
        in.put("size", size);
        in.put("page", page);
        return in;
    }

    /** 모든 쪽을 넘겨 (유형:id) 목록을 모은다. */
    private List<String> walk(long user, String q, String sort, int size, int total) throws Exception {
        List<String> seen = new ArrayList<>();
        for (int p = 0; p * size < total; p++) {
            JsonNode result = search(user, input(q, sort, size, p));
            assertThat(result.get("total").asInt()).isEqualTo(total);
            result.get("hits").forEach(h -> seen.add(h.get("docType").asText() + ":" + h.get("id").asText()));
        }
        return seen;
    }

    @Test
    void 같은_시각의_페이지와_첨부를_여러_쪽에_넘겨도_겹침과_빠짐이_없다() throws Exception {
        Instant same = Instant.parse("2026-05-05T05:05:05Z");
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            Page p = page("동률 문서 " + i);
            at("page", p.getId(), same);
            expected.add("PAGE:" + p.getId());
            Attachment a = attachments.save(Attachment.of(p.getId(), "동률-" + i + ".txt", "text/plain", 1L, "k" + i, USER));
            at("attachment", a.getId(), same);
            expected.add("ATTACHMENT:" + a.getId());
        }
        // 제한 문서 하나(인라인 상한 2 이하 → SQL 제외 경로)와 그 첨부는 빠져야 한다
        Page hidden = page("동률 기밀");
        at("page", hidden.getId(), same);
        restrict(hidden);

        for (String sort : List.of("RELEVANCE", "UPDATED_DESC", "UPDATED_ASC")) {
            List<String> seen = walk(OTHER, "동률", sort, 3, expected.size());
            assertThat(seen).as(sort).doesNotHaveDuplicates();
            assertThat(new HashSet<>(seen)).as(sort).isEqualTo(expected);
        }
    }

    @Test
    void 숨길_문서가_인라인_상한을_넘으면_스캔_경로이고_상한을_넘으면_하한_total이다() throws Exception {
        Page parent = page("묶음 상위");
        restrict(parent);
        for (int i = 0; i < 3; i++) pages.save(Page.of(space.getId(), parent.getId(), "기밀 " + i, "본문", USER));
        List<Long> visible = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Page p = page("점검 " + i);
            at("page", p.getId(), Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i));
            visible.add(p.getId());
        }

        // 일치 4건 ≤ 스캔 상한 5 → 숨김 4건(상위+자식 3)이 인라인 상한 2를 넘어도 정확하다
        JsonNode exact = search(OTHER, input("점검", "UPDATED_ASC", 10, 0));
        assertThat(exact.get("totalExact").asBoolean()).isTrue();
        assertThat(exact.get("total").asInt()).isEqualTo(4);

        // 일치가 스캔 상한(5)을 넘으면 total은 하한 — 거짓으로 "정확"이라 하지 않는다
        for (int i = 4; i < 9; i++) {
            Page p = page("점검 " + i);
            at("page", p.getId(), Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i));
            visible.add(p.getId());
        }
        JsonNode capped = search(OTHER, input("점검", "UPDATED_ASC", 10, 0));
        assertThat(capped.get("totalExact").asBoolean()).isFalse();
        assertThat(capped.get("total").asInt()).isEqualTo(5);
        List<Long> got = new ArrayList<>();
        capped.get("hits").forEach(h -> got.add(h.get("id").asLong()));
        assertThat(got).containsExactlyElementsOf(visible.subList(0, 5)); // 정렬 앞부분 그대로
    }

    @Test
    void 요청_도중_걸린_제한은_본문을_읽은_뒤의_재판정이_막는다() throws Exception {
        Page secret = page("배포 기밀");
        Page open = page("배포 공개");
        restrict(secret);
        // 낡은 스냅샷 재현: 첫 숨김 집합 계산이 제한을 보기 전에 끝난 것처럼 비운다(그 뒤로는 실제 값).
        doReturn(Set.of()).doCallRealMethod().when(effective).hiddenPageIds(anyLong(), anyCollection());

        JsonNode result = search(OTHER, input("배포", "RELEVANCE", 10, 0));
        List<String> ids = new ArrayList<>();
        result.get("hits").forEach(h -> ids.add(h.get("id").asText()));
        assertThat(ids).containsExactly(String.valueOf(open.getId()));
        // 건수도 다시 센다 — 본문만 막고 total에 남기면 "그 검색어가 든 문서가 있다"가 샌다
        assertThat(result.get("total").asInt()).isEqualTo(1);
        assertThat(result.get("totalExact").asBoolean()).isTrue();
    }

    @Test
    void 크기_0_요청도_건수에_새로_숨긴_문서를_세지_않는다() throws Exception {
        Page secret = page("배포 기밀");
        restrict(secret);
        doReturn(Set.of()).doCallRealMethod().when(effective).hiddenPageIds(anyLong(), anyCollection());
        JsonNode result = search(OTHER, input("배포", "RELEVANCE", 0, 0));
        assertThat(result.get("total").asInt()).isZero();
    }

    @Test
    void 제한이_계속_바뀌어_확정할_수_없으면_비우고_하한임을_알린다() throws Exception {
        Page p1 = page("배포 하나");
        page("배포 둘");
        // 매번 새 문서가 숨겨지는 상황(계속 바뀌는 중) — 질의 전후 집합이 끝내 같아지지 않는다
        java.util.concurrent.atomic.AtomicLong n = new java.util.concurrent.atomic.AtomicLong();
        org.mockito.Mockito.doAnswer(inv -> Set.of(p1.getId(), -n.incrementAndGet()))
                .when(effective).hiddenPageIds(anyLong(), anyCollection());
        JsonNode result = search(OTHER, input("배포", "RELEVANCE", 10, 0));
        assertThat(result.get("total").asInt()).isZero();
        assertThat(result.get("totalExact").asBoolean()).isFalse();
        assertThat(result.get("hits").size()).isZero();
    }
}
