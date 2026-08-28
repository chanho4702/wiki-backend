package com.platform.wikibackend.page;

import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 지연 트리 API(2026-08-28) — 화면 단위 조회와 페이지 제한 필터. */
@SpringBootTest
@ActiveProfiles("test")
class TreeApiTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    Space space;
    long root, child, grand, sibling;
    static final long EDITOR = 1L;
    static final long OUTSIDER = 2L;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        restrictions.deleteAll();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, EDITOR));
        for (long user : new long[]{EDITOR, OUTSIDER}) {
            perms.allow(user, space.getId(), WikiAction.VIEW);
            perms.allow(user, space.getId(), WikiAction.EDIT);
        }
        root = create(null, "배포 가이드");
        child = create(root, "롤백 절차");
        grand = create(child, "체크리스트");
        sibling = create(null, "온보딩");
    }

    private long create(Long parentId, String title) throws Exception {
        String parent = parentId == null ? "null" : String.valueOf(parentId);
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":" + parent
                                + ",\"title\":\"" + title + "\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    /** childCount가 없으면 트리가 펼침 화살표를 그리려고 결국 전부 미리 불러오게 된다. */
    @Test
    void 루트_목록은_직계만_주고_자식_수를_함께_준다() throws Exception {
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/children")
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("배포 가이드"))
                .andExpect(jsonPath("$[0].childCount").value(1))
                .andExpect(jsonPath("$[1].title").value("온보딩"))
                .andExpect(jsonPath("$[1].childCount").value(0));
    }

    @Test
    void 자식_목록은_그_아래_한_단계만_준다() throws Exception {
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/children")
                        .param("parentId", String.valueOf(root)).with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("롤백 절차"))
                .andExpect(jsonPath("$[0].childCount").value(1));
    }

    @Test
    void 조상_체인은_루트부터_부모까지_순서대로_준다() throws Exception {
        mvc.perform(get("/api/wiki/pages/" + grand + "/ancestors").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("배포 가이드"))
                .andExpect(jsonPath("$[1].title").value("롤백 절차"));

        mvc.perform(get("/api/wiki/pages/" + sibling + "/ancestors").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 후손_폐포는_자기_자신을_빼고_전부_준다() throws Exception {
        mvc.perform(get("/api/wiki/pages/" + root + "/descendants").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(2));
    }

    /** `[[제목]]` 해석은 렌더러와 같은 기준이어야 한다 — trim + 소문자, 같은 스페이스. */
    @Test
    void 제목_조회는_대소문자와_공백을_무시하고_맞춘다() throws Exception {
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/lookup")
                        .param("title", "  배포 가이드 ").param("title", "없는 문서")
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) root));
    }

    @Test
    void 제목_검색은_부분_일치로_찾는다() throws Exception {
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/search")
                        .param("q", "절차").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("롤백 절차"));

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/search")
                        .param("q", "  ").with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * 새 조회 경로가 W18 제한을 우회하면 그 경로가 곧 누출구다.
     * 자식·검색·제목조회·후손 어디에서도 제한된 문서가 새어 나오면 안 된다.
     */
    @Test
    void 제한된_페이지는_모든_지연_트리_경로에서_빠진다() throws Exception {
        restrictions.save(PageRestriction.of(child, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, EDITOR, EDITOR));

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/children")
                        .param("parentId", String.valueOf(root)).with(asUser(OUTSIDER, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/search")
                        .param("q", "절차").with(asUser(OUTSIDER, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/lookup")
                        .param("title", "롤백 절차").with(asUser(OUTSIDER, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));
        // 상속이므로 손자도 함께 가려진다
        mvc.perform(get("/api/wiki/pages/" + root + "/descendants").with(asUser(OUTSIDER, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/wiki/pages/" + grand + "/ancestors").with(asUser(OUTSIDER, "Bob")))
                .andExpect(status().isForbidden());
    }

    /** 별표 목록처럼 "아는 id들의 현재 제목"이 필요한 곳 — 다른 스페이스 id는 섞여도 안 나온다. */
    @Test
    void id_묶음_조회는_같은_스페이스의_볼_수_있는_문서만_준다() throws Exception {
        Space other = spaces.save(Space.of("ops", "운영", null, EDITOR));
        perms.allow(EDITOR, other.getId(), WikiAction.VIEW);
        perms.allow(EDITOR, other.getId(), WikiAction.EDIT);
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + other.getId()
                                + ",\"parentId\":null,\"title\":\"남의 문서\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long foreign = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/by-ids")
                        .param("id", String.valueOf(root))
                        .param("id", String.valueOf(foreign))
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("배포 가이드"));

        restrictions.save(PageRestriction.of(root, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, EDITOR, EDITOR));
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/by-ids")
                        .param("id", String.valueOf(root)).with(asUser(OUTSIDER, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 개요 화면의 "최근 업데이트" — 전량을 읽어 정렬하던 것을 대체한다. */
    @Test
    void 최근_수정_목록은_최신순으로_요청한_개수만_준다() throws Exception {
        mvc.perform(put("/api/wiki/pages/" + sibling).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"온보딩\",\"content\":\"고침\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/recent")
                        .param("limit", "2").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("온보딩"));
    }

    @Test
    void 제한된_문서는_최근_수정_목록에서도_빠진다() throws Exception {
        restrictions.save(PageRestriction.of(child, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, EDITOR, EDITOR));

        String body = mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/recent")
                        .param("limit", "10").with(asUser(OUTSIDER, "Bob")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("롤백 절차");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("체크리스트");
    }

    @Test
    void 휴지통에_들어간_문서는_지연_트리에_나오지_않는다() throws Exception {
        mvc.perform(delete("/api/wiki/pages/" + sibling).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/children")
                        .with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
