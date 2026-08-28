package com.platform.wikibackend.label;

import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageLabelRepository;
import com.platform.wikibackend.repository.PageLinkRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
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

import com.platform.wikibackend.TestPages;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 라벨·백링크(W21-2). */
@SpringBootTest
@ActiveProfiles("test")
class LabelTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageLabelRepository labels;
    @Autowired PageLinkRepository links;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    Space space;
    static final long EDITOR = 1L;
    static final long VIEWER = 2L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        labels.deleteAll();
        links.deleteAll();
        restrictions.deleteAll();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, EDITOR));
        perms.allow(EDITOR, space.getId(), WikiAction.VIEW);
        perms.allow(EDITOR, space.getId(), WikiAction.EDIT);
        perms.allow(VIEWER, space.getId(), WikiAction.VIEW);
    }

    private long createPage(String title, String content) throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":null,\"title\":\""
                                + title + "\",\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    private void putLabels(long pageId, long user, String json) throws Exception {
        mvc.perform(put("/api/wiki/pages/" + pageId + "/labels").with(asUser(user, "U"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":" + json + "}"))
                .andExpect(status().isOk());
    }

    /** 대소문자·공백만 다른 라벨이 갈라지면 목록이 금세 쓸모없어진다. */
    @Test
    void 라벨은_정규화되고_중복은_하나로_합쳐진다() throws Exception {
        long page = createPage("보고서", "본문");

        mvc.perform(put("/api/wiki/pages/" + page + "/labels").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[\" Design \",\"design\",\"기획 문서\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("design"))
                .andExpect(jsonPath("$[1]").value("기획-문서"));
    }

    @Test
    void 라벨_수정은_EDIT_권한이_필요하다() throws Exception {
        long page = createPage("보고서", "본문");

        mvc.perform(put("/api/wiki/pages/" + page + "/labels").with(asUser(VIEWER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[\"design\"]}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/wiki/pages/" + page + "/labels").with(asUser(VIEWER, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 스페이스_라벨_목록은_사용_횟수와_함께_나오고_라벨로_페이지를_찾는다() throws Exception {
        long a = createPage("가", "본문");
        long b = createPage("나", "본문");
        putLabels(a, EDITOR, "[\"design\",\"api\"]");
        putLabels(b, EDITOR, "[\"design\"]");

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/labels").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("design"))
                .andExpect(jsonPath("$[0].count").value(2));

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/labels/design/pages")
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/labels/api/pages")
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(1));
    }

    /** 버린 문서의 라벨이 목록에 남으면 클릭했을 때 빈 결과가 나온다. */
    @Test
    void 휴지통에_들어간_페이지의_라벨은_목록에서_빠진다() throws Exception {
        long page = createPage("보고서", "본문");
        putLabels(page, EDITOR, "[\"design\"]");

        mvc.perform(delete("/api/wiki/pages/" + page).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/labels").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 본문의_내부링크가_대상의_백링크로_잡힌다() throws Exception {
        long target = createPage("배포 가이드", "본문");
        long source = createPage("온보딩", "자세한 건 [[배포 가이드]] 참고");

        mvc.perform(get("/api/wiki/pages/" + target + "/backlinks").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("온보딩"));

        // 링크를 지우고 저장하면 백링크도 사라진다 — 그래프는 본문의 파생물이다
        mvc.perform(put("/api/wiki/pages/" + source).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"온보딩\",\"content\":\"링크 없음\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/wiki/pages/" + target + "/backlinks").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 코드 예시 속 대괄호가 링크로 잡히면 문서 그래프가 오염된다(렌더러도 코드 밖에서만 치환한다). */
    @Test
    void 코드_구간의_대괄호는_링크로_잡지_않는다() throws Exception {
        long target = createPage("배포 가이드", "본문");
        createPage("샘플", "인라인 `[[배포 가이드]]` 와 펜스\\n```\\n[[배포 가이드]]\\n```");

        mvc.perform(get("/api/wiki/pages/" + target + "/backlinks").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 볼_수_없는_페이지는_백링크_목록에_나오지_않는다() throws Exception {
        long target = createPage("배포 가이드", "본문");
        long secret = createPage("비밀 메모", "[[배포 가이드]]");
        restrictions.save(PageRestriction.of(secret, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, EDITOR, EDITOR));

        mvc.perform(get("/api/wiki/pages/" + target + "/backlinks").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/wiki/pages/" + target + "/backlinks").with(asUser(VIEWER, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 영구_삭제는_라벨과_링크_행도_치운다() throws Exception {
        long page = createPage("보고서", "[[없는 문서]]");
        putLabels(page, EDITOR, "[\"design\"]");
        perms.allow(EDITOR, space.getId(), WikiAction.ADMIN);

        mvc.perform(delete("/api/wiki/pages/" + page).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/wiki/pages/" + page + "/purge").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(labels.findByPageIdOrderByName(page)).isEmpty();
        assertThat(links.findAll()).isEmpty();
    }
}
