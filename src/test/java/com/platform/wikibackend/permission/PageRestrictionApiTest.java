package com.platform.wikibackend.permission;

import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.Space;
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

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** W18 증분 2 — 제한 관리 API(GET/PUT /pages/{id}/restrictions) 접근 규칙·교체·상속 표시. */
@SpringBootTest
@ActiveProfiles("test")
class PageRestrictionApiTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient perms;
    @Autowired FakeTeamDirectory teams;
    MockMvc mvc;

    Space space;
    static final long ALICE = 1L;
    static final long BOB = 2L;
    static final long ADMIN = 3L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        restrictions.deleteAll();
        revisions.deleteAll();
        pages.deleteAll();
        spaces.deleteAll();
        perms.reset();
        teams.reset();
        space = spaces.save(Space.of("dev", "개발", null, ALICE));
        for (long u : new long[] {ALICE, BOB, ADMIN}) {
            perms.allow(u, space.getId(), WikiAction.VIEW);
            perms.allow(u, space.getId(), WikiAction.EDIT);
        }
        perms.allow(ADMIN, space.getId(), WikiAction.ADMIN);
    }

    private long createPage(Long parentId, String title) throws Exception {
        String parent = parentId == null ? "null" : String.valueOf(parentId);
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":" + parent
                                + ",\"title\":\"" + title + "\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    @Test
    void 교체와_조회_그리고_상속_표시() throws Exception {
        long parent = createPage(null, "상위 문서");
        long child = createPage(parent, "하위 문서");

        // 앨리스가 상위에 VIEW 제한(자신 포함) 설정
        mvc.perform(put("/api/wiki/pages/" + parent + "/restrictions").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":[{\"type\":\"USER\",\"id\":1}],\"edit\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view[0].id").value(1))
                .andExpect(jsonPath("$.edit.length()").value(0));

        // 실제로 밥이 상위·하위를 못 보게 된다(적용 확인)
        mvc.perform(get("/api/wiki/pages/" + child).with(asUser(BOB, "밥")))
                .andExpect(status().isForbidden());

        // 하위 문서의 제한 조회 — 자기 제한은 없고 상위에서 상속됨이 표시된다
        mvc.perform(get("/api/wiki/pages/" + child + "/restrictions").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.length()").value(0))
                .andExpect(jsonPath("$.inherited[0].pageId").value(parent))
                .andExpect(jsonPath("$.inherited[0].pageTitle").value("상위 문서"))
                .andExpect(jsonPath("$.inherited[0].principals[0].id").value(1));
    }

    @Test
    void 제한_통과_못하는_사용자는_관리도_못하지만_ADMIN은_할_수_있다() throws Exception {
        long id = createPage(null, "제한 문서");
        restrictions.save(PageRestriction.of(id, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, ALICE, ALICE));

        mvc.perform(get("/api/wiki/pages/" + id + "/restrictions").with(asUser(BOB, "밥")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/wiki/pages/" + id + "/restrictions").with(asUser(BOB, "밥"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":[],\"edit\":[]}"))
                .andExpect(status().isForbidden());

        // ADMIN은 제한 관리 가능(본문과 달리 — ADR 규칙 6) — 제한 해제
        mvc.perform(put("/api/wiki/pages/" + id + "/restrictions").with(asUser(ADMIN, "관리자"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":[],\"edit\":[]}"))
                .andExpect(status().isOk());
        assertThat(restrictions.findByPageId(id)).isEmpty();
        mvc.perform(get("/api/wiki/pages/" + id).with(asUser(BOB, "밥")))
                .andExpect(status().isOk());
    }

    @Test
    void 셀프_락아웃은_400_단_ADMIN과_TEAM_지정은_허용() throws Exception {
        long id = createPage(null, "문서");

        // 자신이 빠진 VIEW 제한 — 저장 직후 자기 문서를 못 보게 되는 실수 방지
        mvc.perform(put("/api/wiki/pages/" + id + "/restrictions").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":[{\"type\":\"USER\",\"id\":2}],\"edit\":[]}"))
                .andExpect(status().isBadRequest());

        // TEAM 지정은 org 왕복 없이 소속을 단정할 수 없어 가드에서 제외(통과)
        mvc.perform(put("/api/wiki/pages/" + id + "/restrictions").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":[{\"type\":\"TEAM\",\"id\":77}],\"edit\":[]}"))
                .andExpect(status().isOk());
    }

    @Test
    void 잘못된_주체_타입은_400() throws Exception {
        long id = createPage(null, "문서");
        mvc.perform(put("/api/wiki/pages/" + id + "/restrictions").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":[{\"type\":\"GROUP\",\"id\":1}],\"edit\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 제한된_부모_아래로_이동은_확인_없이는_409_impact_확인하면_실행() throws Exception {
        long restricted = createPage(null, "제한 폴더");
        long moving = createPage(null, "옮길 문서");
        restrictions.save(PageRestriction.of(restricted, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, ALICE, ALICE));

        // 1차: 영향 발견 → 409 + impact(새로 적용될 제한 노드)
        mvc.perform(post("/api/wiki/pages/" + moving + "/move").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + restricted + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.impact.newlyRestrictedBy[0].pageId").value(restricted))
                .andExpect(jsonPath("$.impact.newlyRestrictedBy[0].pageTitle").value("제한 폴더"))
                .andExpect(jsonPath("$.impact.newlyRestrictedBy[0].principals[0].id").value(1));

        // 2차: confirmImpact → 실행
        mvc.perform(post("/api/wiki/pages/" + moving + "/move").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + restricted + ",\"confirmImpact\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(restricted));

        // 이미 그 제한 아래인 페이지의 재이동(형제 재정렬·같은 체인)은 영향 없음
        mvc.perform(post("/api/wiki/pages/" + moving + "/move").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null,\"confirmImpact\":false}"))
                .andExpect(status().isOk()); // 제한 밖으로 나가는 건 접근 "상실"이 아니다
    }
}
