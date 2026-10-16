package com.platform.wikibackend.page;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageComment;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 목록 규모 회귀(2026-10-16) — 이력·댓글 쪽 목록, 본문을 읽지 않는 목록, 제한 뒤 채우기.
 *
 * 비용 가드는 Hibernate 통계로 건다: "본문을 든 엔티티를 몇 건 로드했는가"는 문서 크기·버전 수에
 * 비례하는 비용의 직접 지표다. 응답 시간으로 거는 가드는 기계마다 흔들려 쓸모가 없다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class ListPaginationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final long EDITOR = 1L;
    static final long OUTSIDER = 2L;

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageCommentRepository comments;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    MockMvc mvc;

    long spaceId;
    long pageId;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        restrictions.deleteAll();
        jdbc.update("delete from reaction");
        jdbc.update("delete from page_comment");
        jdbc.update("delete from page_revision");
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        Space s = spaces.save(Space.of("list", "목록", null, EDITOR));
        spaceId = s.getId();
        for (long user : new long[]{EDITOR, OUTSIDER}) {
            perms.allow(user, spaceId, WikiAction.VIEW);
            perms.allow(user, spaceId, WikiAction.EDIT);
            perms.allow(user, spaceId, WikiAction.COMMENT);
        }
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + spaceId + ",\"parentId\":null,\"title\":\"t\",\"content\":\"v1\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        pageId = JSON.readTree(body).get("id").asLong();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private long loads(Class<?> entity) {
        return statistics().getEntityStatistics(entity.getName()).getLoadCount();
    }

    private JsonNode getJson(String path, long user, String... params) throws Exception {
        var request = get(path).with(asUser(user, "u" + user));
        for (int i = 0; i < params.length; i += 2) request = request.param(params[i], params[i + 1]);
        return JSON.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /* ── 버전 이력 ───────────────────────────────────────────────── */

    private void editTo(int expectedVersion, String content) throws Exception {
        mvc.perform(put("/api/wiki/pages/" + pageId).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of(
                                "title", "t", "content", content, "expectedVersion", expectedVersion))))
                .andExpect(status().isOk());
    }

    @Test
    void 이력은_버전_커서로_끝까지_한_쪽씩_읽고_본문을_로드하지_않는다() throws Exception {
        // 큰 본문(압축 임계값 위)으로 7개 버전 — 목록이 본문을 읽으면 통계에 그대로 잡힌다.
        String big = "가".repeat(4_000);
        for (int v = 1; v < 7; v++) editTo(v, big + v);

        statistics().clear();
        List<Integer> seen = new ArrayList<>();
        Integer before = null;
        int pagesRead = 0;
        do {
            JsonNode page = before == null
                    ? getJson("/api/wiki/pages/" + pageId + "/revisions/paged", EDITOR, "limit", "3")
                    : getJson("/api/wiki/pages/" + pageId + "/revisions/paged", EDITOR,
                            "limit", "3", "before", String.valueOf(before));
            assertThat(page.get("total").asLong()).isEqualTo(7);
            page.get("items").forEach(item -> seen.add(item.get("version").asInt()));
            before = page.get("nextBefore").isNull() ? null : page.get("nextBefore").asInt();
            pagesRead++;
        } while (before != null);

        assertThat(seen).containsExactly(7, 6, 5, 4, 3, 2, 1);
        assertThat(pagesRead).isEqualTo(3);
        // 전량 계약은 그대로다 — 기존 호출자가 조용히 잘린 목록을 받지 않는다.
        assertThat(getJson("/api/wiki/pages/" + pageId + "/revisions", EDITOR).size()).isEqualTo(7);
        assertThat(loads(PageRevision.class)).isZero();

        // 한 쪽 상한
        assertThat(getJson("/api/wiki/pages/" + pageId + "/revisions/paged", EDITOR, "limit", "1000")
                .get("items").size()).isEqualTo(7);
    }

    @Test
    void 이력_쪽_목록도_페이지_제한을_지킨다() throws Exception {
        restrictions.save(PageRestriction.of(pageId, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, EDITOR, EDITOR));
        mvc.perform(get("/api/wiki/pages/" + pageId + "/revisions/paged").with(asUser(OUTSIDER, "Bob")))
                .andExpect(status().isForbidden());
    }

    /* ── 댓글 스레드 ─────────────────────────────────────────────── */

    private long comment(long user, String body, Long parentId, String anchorQuote) throws Exception {
        java.util.Map<String, Object> req = new java.util.HashMap<>();
        req.put("body", body);
        req.put("parentId", parentId);
        if (anchorQuote != null) {
            req.put("anchorQuote", anchorQuote);
            req.put("anchorOccurrence", 0);
        }
        String res = mvc.perform(post("/api/wiki/pages/" + pageId + "/comments").with(asUser(user, "u" + user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(res).get("id").asLong();
    }

    @Test
    void 댓글은_스레드_단위로_최신부터_끝까지_읽히고_답글은_스레드와_함께_온다() throws Exception {
        List<Long> roots = new ArrayList<>();
        for (int i = 0; i < 5; i++) roots.add(comment(EDITOR, "댓글 " + i, null, null));
        long reply1 = comment(OUTSIDER, "답글 a", roots.get(1), null);
        long reply2 = comment(EDITOR, "답글 b", roots.get(1), null);
        long inline = comment(EDITOR, "인라인", null, "v1");
        comment(OUTSIDER, "인라인 답글", inline, null);
        // 이관 댓글처럼 같은 작성 시각이 여럿이어도 커서가 건너뛰거나 겹치지 않아야 한다.
        jdbc.update("update page_comment set created_at = ? where id in (?, ?, ?)",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z")),
                roots.get(2), roots.get(3), roots.get(4));

        List<Long> seenRoots = new ArrayList<>();
        List<Long> seenReplies = new ArrayList<>();
        String before = null;
        do {
            JsonNode page = before == null
                    ? getJson("/api/wiki/pages/" + pageId + "/comments/threads", EDITOR, "limit", "2")
                    : getJson("/api/wiki/pages/" + pageId + "/comments/threads", EDITOR, "limit", "2", "before", before);
            assertThat(page.get("threadCount").asLong()).isEqualTo(5);
            assertThat(page.get("commentCount").asLong()).isEqualTo(7);
            for (JsonNode item : page.get("items")) {
                if (item.get("parentId").isNull()) seenRoots.add(item.get("id").asLong());
                else {
                    // 답글은 자기 스레드와 같은 쪽에 온다 — 부모 없는 답글을 화면이 받지 않는다.
                    assertThat(seenRoots).contains(item.get("parentId").asLong());
                    seenReplies.add(item.get("id").asLong());
                }
                assertThat(item.get("anchorType").asText()).isEqualTo("page");
            }
            before = page.get("nextBefore").isNull() ? null : page.get("nextBefore").asText();
        } while (before != null);

        // 최신 스레드부터: 시각을 되돌린 2·3·4는 가장 오래된 쪽이고, 같은 시각 안에서는 id 내림차순.
        assertThat(seenRoots).containsExactly(roots.get(1), roots.get(0), roots.get(4), roots.get(3), roots.get(2));
        assertThat(seenReplies).containsExactly(reply1, reply2);

        JsonNode inlinePage = getJson("/api/wiki/pages/" + pageId + "/comments/threads", EDITOR, "kind", "inline");
        assertThat(inlinePage.get("threadCount").asLong()).isEqualTo(1);
        assertThat(inlinePage.get("commentCount").asLong()).isEqualTo(2);
        assertThat(inlinePage.get("items").size()).isEqualTo(2);
        assertThat(inlinePage.get("nextBefore").isNull()).isTrue();

        // 전량 목록은 그대로다
        assertThat(getJson("/api/wiki/pages/" + pageId + "/comments", EDITOR).size()).isEqualTo(9);
    }

    @Test
    void 깨진_커서와_모르는_종류는_400이다() throws Exception {
        mvc.perform(get("/api/wiki/pages/" + pageId + "/comments/threads").param("before", "abc")
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("댓글 목록 커서가 올바르지 않습니다: abc"));
        mvc.perform(get("/api/wiki/pages/" + pageId + "/comments/threads").param("kind", "footer")
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 댓글_삭제는_페이지의_댓글_전량을_읽지_않는다() throws Exception {
        long target = comment(EDITOR, "지울 것", null, null);
        comment(OUTSIDER, "답글", target, null);
        for (int i = 0; i < 20; i++) comment(EDITOR, "남는 댓글 " + i, null, null);

        statistics().clear();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/wiki/comments/" + target).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());
        assertThat(loads(PageComment.class)).isLessThanOrEqualTo(2); // 자신 + 답글
        assertThat(comments.countByPageId(pageId)).isEqualTo(20);
    }

    /* ── 지연 트리: 본문 재조회 없음 + 제한 뒤 채우기 ────────────────────── */

    @Test
    void 트리_조회는_본문을_든_엔티티를_다시_읽지_않는다() throws Exception {
        for (int i = 0; i < 30; i++) pages.save(Page.of(spaceId, pageId, "자식 " + i, "x".repeat(2_000), EDITOR));
        // 판정이 조기 종료하지 않게 상위에 제한을 둔다(EDITOR 본인은 통과).
        restrictions.save(PageRestriction.of(pageId, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, EDITOR, EDITOR));

        statistics().clear();
        assertThat(getJson("/api/wiki/spaces/" + spaceId + "/pages/children", EDITOR,
                "parentId", String.valueOf(pageId)).size()).isEqualTo(30);
        assertThat(getJson("/api/wiki/spaces/" + spaceId + "/pages/search", EDITOR, "q", "자식").size())
                .isEqualTo(30);
        assertThat(loads(Page.class)).isEqualTo(1); // children이 부모 하나를 확인할 때만
    }

    @Test
    void 제목_검색은_앞쪽_후보가_전부_제한_문서여도_볼_수_있는_문서를_준다() throws Exception {
        Page secret = pages.save(Page.of(spaceId, null, "가 기밀 묶음", "본문", EDITOR));
        restrictions.save(PageRestriction.of(secret.getId(), PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, EDITOR, EDITOR));
        // 제목순 정렬에서 앞에 오는 제한 문서가 한 묶음(200)과 상한(50)을 넘는다.
        List<Page> hidden = new ArrayList<>();
        for (int i = 0; i < TreeService.CANDIDATE_BATCH + 60; i++) {
            hidden.add(Page.of(spaceId, secret.getId(), String.format("가 회의록 %04d", i), "본문", EDITOR));
        }
        pages.saveAll(hidden);
        Page open = pages.save(Page.of(spaceId, null, "하 회의록 공개", "본문", EDITOR));

        JsonNode found = getJson("/api/wiki/spaces/" + spaceId + "/pages/search", OUTSIDER, "q", "회의록");
        assertThat(found.size()).isEqualTo(1);
        assertThat(found.get(0).get("id").asLong()).isEqualTo(open.getId());

        // 볼 수 있는 사람에게는 상한(50)까지 꽉 찬다.
        assertThat(getJson("/api/wiki/spaces/" + spaceId + "/pages/search", EDITOR, "q", "회의록").size())
                .isEqualTo(TreeService.SEARCH_LIMIT);
    }
}
