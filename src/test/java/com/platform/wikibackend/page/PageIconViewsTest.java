package com.platform.wikibackend.page;

import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.event.RecordingEventPublisher;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** V10 — 이모지 아이콘·조회수 계약 (프론트 setPageIcon/recordPageView). */
@SpringBootTest
@ActiveProfiles("test")
class PageIconViewsTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired FakePermissionClient perms;
    @Autowired RecordingEventPublisher events;
    MockMvc mvc;

    Space space;
    static final long EDITOR = 1L;
    static final long VIEWER = 2L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        revisions.deleteAll();
        pages.deleteAllIncludingTrashed();
        spaces.deleteAll();
        perms.reset();
        events.reset();
        space = spaces.save(Space.of("dev", "개발", null, EDITOR));
        perms.allow(EDITOR, space.getId(), WikiAction.VIEW);
        perms.allow(EDITOR, space.getId(), WikiAction.EDIT);
        perms.allow(VIEWER, space.getId(), WikiAction.VIEW);
    }

    private long createPage(String title) throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":null,\"title\":\""
                                + title + "\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    @Test
    void 아이콘_설정은_version_리비전_불변으로_영속되고_트리에도_실린다() throws Exception {
        long id = createPage("문서");

        mvc.perform(put("/api/wiki/pages/" + id + "/icon").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"icon\":\"🚀\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icon").value("🚀"))
                .andExpect(jsonPath("$.version").value(1)); // 메타데이터 — 버전 불변

        assertThat(revisions.findByPageIdAndVersion(id, 2)).isEmpty(); // 리비전도 없음
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages").with(asUser(VIEWER, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].icon").value("🚀"));

        // null = 해제
        mvc.perform(put("/api/wiki/pages/" + id + "/icon").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"icon\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icon").doesNotExist());
    }

    @Test
    void 아이콘은_EDIT_권한이_필요하다() throws Exception {
        long id = createPage("문서");
        mvc.perform(put("/api/wiki/pages/" + id + "/icon").with(asUser(VIEWER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"icon\":\"🚀\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 조회_기록은_VIEW_권한으로_누적치를_돌려준다() throws Exception {
        long id = createPage("문서");

        mvc.perform(post("/api/wiki/pages/" + id + "/views").with(asUser(VIEWER, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views").value(1));
        mvc.perform(post("/api/wiki/pages/" + id + "/views").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views").value(2));

        mvc.perform(get("/api/wiki/pages/" + id).with(asUser(VIEWER, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views").value(2));
    }

    @Test
    void 없는_페이지는_404() throws Exception {
        mvc.perform(post("/api/wiki/pages/999999/views").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNotFound());
    }
}
