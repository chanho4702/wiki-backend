package com.platform.wikibackend.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import com.platform.wikibackend.repository.SpaceWatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 초안 노출 정책(2026-10-18 사용자 결정, 감사 W-22).
 *
 * 초안은 작성자 + 스페이스 관리자 + 전역 관리자만 조회·목록·트리·검색(라이트)·알림·최근 변경에서 본다.
 * 게시하면 정상 노출된다. 서버가 강제한다 — 화면이 숨기는 것으로는 API·검색·알림으로 샌다.
 * 응답 모양은 바뀌지 않는다(보이는 것만 줄어든다).
 */
@SpringBootTest
@ActiveProfiles("test")
class DraftVisibilityTest {

    static final long AUTHOR = 1L;
    static final long READER = 2L;      // 같은 스페이스 편집자 — 남의 초안은 못 본다
    static final long SPACE_ADMIN = 3L;
    static final long GLOBAL_ADMIN = 4L;

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired SpaceWatchRepository spaceWatches;
    @Autowired NotificationRepository notifications;
    @Autowired FakePermissionClient perms;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    MockMvc mvc;
    Space space;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAll();
        spaceWatches.deleteAll();
        jdbc.update("delete from page_revision");
        jdbc.update("delete from page_watch");
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("draft" + (System.nanoTime() % 100000), "초안", null, AUTHOR));
        for (long u : new long[] {AUTHOR, READER}) {
            perms.allow(u, space.getId(), WikiAction.VIEW);
            perms.allow(u, space.getId(), WikiAction.EDIT);
        }
        perms.allow(SPACE_ADMIN, space.getId(), WikiAction.VIEW);
        perms.allow(SPACE_ADMIN, space.getId(), WikiAction.ADMIN);
        perms.allow(GLOBAL_ADMIN, space.getId(), WikiAction.VIEW);
        perms.allowGlobalAdmin(GLOBAL_ADMIN);
    }

    @Test
    void 초안은_작성자와_관리자만_열_수_있다() throws Exception {
        long draft = create(AUTHOR, null, "작성 중인 기획", "draft");

        open(AUTHOR, draft).andExpect(status().isOk());
        open(SPACE_ADMIN, draft).andExpect(status().isOk());
        open(GLOBAL_ADMIN, draft).andExpect(status().isOk());
        open(READER, draft).andExpect(status().isForbidden());
    }

    @Test
    void 트리와_최근_변경에서_남의_초안과_그_하위가_빠진다() throws Exception {
        long published = create(AUTHOR, null, "게시 문서", "published");
        long draft = create(AUTHOR, null, "초안 폴더", "draft");
        create(AUTHOR, draft, "초안 아래 문서", "published");

        mvc.perform(get("/api/wiki/spaces/{id}/pages/children", space.getId()).with(asUser(READER, "Reader")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(published));
        mvc.perform(get("/api/wiki/spaces/{id}/pages/recent", space.getId()).with(asUser(READER, "Reader")))
                .andExpect(jsonPath("$[*].title", not(hasItem("초안 폴더"))))
                .andExpect(jsonPath("$[*].title", not(hasItem("초안 아래 문서"))));
        mvc.perform(get("/api/wiki/spaces/{id}/pages/search", space.getId()).param("q", "초안")
                        .with(asUser(READER, "Reader")))
                .andExpect(jsonPath("$.length()").value(0));

        // 작성자·관리자는 그대로 본다
        mvc.perform(get("/api/wiki/spaces/{id}/pages/children", space.getId()).with(asUser(AUTHOR, "Author")))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/wiki/spaces/{id}/pages/children", space.getId()).with(asUser(SPACE_ADMIN, "Admin")))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void 라이트_검색은_초안_포함을_요청해도_남의_초안을_주지_않는다() throws Exception {
        create(AUTHOR, null, "고유초안제목", "draft");

        search(READER, Map.of("query", "고유초안제목", "includeDrafts", true))
                .andExpect(jsonPath("$.data.search.total").value(0));
        search(AUTHOR, Map.of("query", "고유초안제목", "includeDrafts", true))
                .andExpect(jsonPath("$.data.search.total").value(1));
    }

    @Test
    void 초안_수정은_구독자에게_알리지_않고_게시하면_알린다() throws Exception {
        mvc.perform(put("/api/wiki/spaces/{id}/watch", space.getId()).with(asUser(READER, "Reader")))
                .andExpect(status().isOk());
        long draft = create(AUTHOR, null, "알림 초안", "draft");
        update(AUTHOR, draft, "알림 초안", "고쳤다", 1);

        assertThat(notifications.findByUserIdAndReadAtIsNull(READER)).isEmpty();

        mvc.perform(post("/api/wiki/pages/{id}/publish", draft).with(asUser(AUTHOR, "Author")))
                .andExpect(status().isOk());

        assertThat(notifications.findByUserIdAndReadAtIsNull(READER)).hasSize(1);
        open(READER, draft).andExpect(status().isOk());
    }

    @Test
    void 블로그_초안도_목록에서_빠진다() throws Exception {
        create(AUTHOR, null, "블로그 초안", "draft", "blog");
        long post = create(AUTHOR, null, "블로그 게시", "published", "blog");

        mvc.perform(get("/api/wiki/spaces/{id}/blog", space.getId()).with(asUser(READER, "Reader")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(post));
        mvc.perform(get("/api/wiki/spaces/{id}/blog", space.getId()).with(asUser(AUTHOR, "Author")))
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── 도우미 ──

    private ResultActions open(long userId, long pageId) throws Exception {
        return mvc.perform(get("/api/wiki/pages/{id}", pageId).with(asUser(userId, "u" + userId)));
    }

    private long create(long userId, Long parentId, String title, String status) throws Exception {
        return create(userId, parentId, title, status, "page");
    }

    private long create(long userId, Long parentId, String title, String status, String type) throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(userId, "u" + userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":" + parentId
                                + ",\"title\":\"" + title + "\",\"content\":\"본문\",\"status\":\"" + status
                                + "\",\"type\":\"" + type + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private void update(long userId, long pageId, String title, String content, int expectedVersion) throws Exception {
        mvc.perform(put("/api/wiki/pages/{id}", pageId).with(asUser(userId, "u" + userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + content
                                + "\",\"expectedVersion\":" + expectedVersion + "}"))
                .andExpect(status().isOk());
    }

    private ResultActions search(long userId, Map<String, Object> input) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "operationName", "Search",
                "query", "query Search($input: SearchInput!) { search(input: $input) { total } }",
                "variables", Map.of("input", input)));
        return mvc.perform(post("/graphql").with(asUser(userId, "u" + userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
