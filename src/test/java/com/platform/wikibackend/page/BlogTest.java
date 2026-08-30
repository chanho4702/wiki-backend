package com.platform.wikibackend.page;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.page.dto.BlogPostView;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 블로그(W24) — 글은 페이지지만 트리에 없고, 목록은 최신순·권한 필터. */
@SpringBootTest
@ActiveProfiles("test")
class BlogTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRevisionRepository revisions;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    Space space;
    static final long ALICE = 1L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, ALICE));
        perms.allow(ALICE, space.getId(), WikiAction.VIEW);
        perms.allow(ALICE, space.getId(), WikiAction.EDIT);
    }

    private long create(String type, Long parentId, String title, String content) throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":" + parentId
                                + ",\"title\":\"" + title + "\",\"content\":\"" + content
                                + "\",\"type\":\"" + type + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void 블로그_글은_트리_루트에_없고_블로그_목록에_최신순으로_있다() throws Exception {
        create("page", null, "일반 문서", "본문");
        long first = create("blog", null, "첫 소식", "# 제목\\n\\n**굵게** [링크](http://x) 본문");
        long second = create("blog", null, "둘째 소식", "본문");

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/children").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("일반 문서"));

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/blog").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].id").value(first))
                .andExpect(jsonPath("$[1].excerpt").value("제목 굵게 링크 본문"));
    }

    @Test
    void 블로그_글은_부모를_가질_수_없고_옮길_수_없다() throws Exception {
        long folder = create("folder", null, "폴더", "");
        mvc.perform(post("/api/wiki/pages").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":" + folder
                                + ",\"title\":\"글\",\"content\":\"\",\"type\":\"blog\"}"))
                .andExpect(status().isBadRequest());

        long post = create("blog", null, "글", "본문");
        mvc.perform(post("/api/wiki/pages/" + post + "/move").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + folder + "}"))
                .andExpect(status().isBadRequest());

        // 글 아래에 문서를 두는 것도 막는다 — 글은 트리의 노드가 아니다
        mvc.perform(post("/api/wiki/pages").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":" + post
                                + ",\"title\":\"하위\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 발췌는_마크다운_기호를_걷어내고_200자에서_자른다() {
        assertThat(BlogPostView.excerptOf("- [ ] 할 일\n> 인용\n```\ncode\n```\n`x`")).isEqualTo("할 일 인용 x");
        assertThat(BlogPostView.excerptOf("가".repeat(300))).hasSize(201).endsWith("…");
    }
}
