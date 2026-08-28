package com.platform.wikibackend.page;

import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
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

import com.platform.wikibackend.TestPages;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class RevisionTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    long pageId;
    static final long EDITOR = 1L;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        Space s = spaces.save(Space.of("dev", "개발", null, EDITOR));
        perms.allow(EDITOR, s.getId(), WikiAction.VIEW);
        perms.allow(EDITOR, s.getId(), WikiAction.EDIT);
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + s.getId() + ",\"parentId\":null,\"title\":\"t\",\"content\":\"v1\"}"))
                .andReturn().getResponse().getContentAsString();
        pageId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    private void editTo(String content, int expectedVersion) throws Exception {
        mvc.perform(put("/api/wiki/pages/" + pageId).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"" + content + "\",\"parentId\":null,\"expectedVersion\":" + expectedVersion + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void expectedVersion_불일치는_409() throws Exception {
        editTo("v2", 1);
        mvc.perform(put("/api/wiki/pages/" + pageId).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"늦은 저장\",\"parentId\":null,\"expectedVersion\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void 리비전_목록과_특정_버전_본문을_조회한다() throws Exception {
        editTo("v2", 1);

        mvc.perform(get("/api/wiki/pages/" + pageId + "/revisions").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].content").doesNotExist()); // 목록은 메타만

        mvc.perform(get("/api/wiki/pages/" + pageId + "/revisions/1").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("v1"));
    }

    @Test
    void 복원은_롤백이_아니라_새_버전이다() throws Exception {
        editTo("v2", 1);

        mvc.perform(post("/api/wiki/pages/" + pageId + "/revisions/1/restore").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.content").value("v1"));

        mvc.perform(get("/api/wiki/pages/" + pageId + "/revisions").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(3)); // 이력 보존
    }

    @Test
    void 없는_버전_복원은_404() throws Exception {
        mvc.perform(post("/api/wiki/pages/" + pageId + "/revisions/99/restore").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNotFound());
    }
}
