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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import com.platform.wikibackend.TestPages;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 휴지통(W21-1) — 소프트 삭제·복원 묶음·영구 삭제 권한·누출 방지. */
@SpringBootTest
@ActiveProfiles("test")
class TrashTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient perms;
    @Autowired TrashService trash;
    MockMvc mvc;

    Space space;
    static final long ADMIN = 1L;
    static final long EDITOR = 2L;
    static final long OUTSIDER = 3L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        restrictions.deleteAll();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, ADMIN));
        for (long user : new long[]{ADMIN, EDITOR}) {
            perms.allow(user, space.getId(), WikiAction.VIEW);
            perms.allow(user, space.getId(), WikiAction.EDIT);
        }
        perms.allow(ADMIN, space.getId(), WikiAction.ADMIN);
        perms.allow(OUTSIDER, space.getId(), WikiAction.VIEW);
        perms.allow(OUTSIDER, space.getId(), WikiAction.EDIT);
    }

    private long createPage(Long parentId, String title) throws Exception {
        String parent = parentId == null ? "null" : String.valueOf(parentId);
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":" + parent
                                + ",\"title\":\"" + title + "\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    private void deletePage(long id, String children) throws Exception {
        String query = children == null ? "" : "?children=" + children;
        mvc.perform(delete("/api/wiki/pages/" + id + query).with(asUser(EDITOR, "Bob")))
                .andExpect(status().isNoContent());
    }

    @Test
    void 삭제한_페이지는_휴지통에_뜨고_조회_경로에서는_사라진다() throws Exception {
        long page = createPage(null, "보고서");
        deletePage(page, null);

        mvc.perform(get("/api/wiki/pages/" + page).with(asUser(EDITOR, "Bob")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/children").with(asUser(EDITOR, "Bob")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/trash").with(asUser(EDITOR, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("보고서"))
                .andExpect(jsonPath("$[0].descendantCount").value(0));
    }

    @Test
    void cascade로_버린_하위는_행이_아니라_개수로_보이고_함께_복원된다() throws Exception {
        long root = createPage(null, "루트");
        long child = createPage(root, "자식");
        long grand = createPage(child, "손자");
        deletePage(root, "cascade");

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/trash").with(asUser(EDITOR, "Bob")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].descendantCount").value(2));

        mvc.perform(post("/api/wiki/pages/" + root + "/restore").with(asUser(EDITOR, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredCount").value(3))
                .andExpect(jsonPath("$.reparentedToRoot").value(false));

        assertThat(pages.findById(child)).isPresent();
        assertThat(pages.findById(grand)).isPresent();
        assertThat(pages.findById(grand).orElseThrow().getParentId()).isEqualTo(child);
    }

    /** 두 번에 걸쳐 버린 것을 한 번의 복원으로 합치지 않는다 — 사용자의 두 결정을 각각 지킨다. */
    @Test
    void 따로_버린_하위_묶음은_상위_복원에_휩쓸리지_않는다() throws Exception {
        long root = createPage(null, "루트");
        long child = createPage(root, "자식");
        deletePage(child, null);
        deletePage(root, null);

        mvc.perform(post("/api/wiki/pages/" + root + "/restore").with(asUser(EDITOR, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredCount").value(1));

        assertThat(pages.findById(root)).isPresent();
        assertThat(pages.findById(child)).isEmpty();
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/trash").with(asUser(EDITOR, "Bob")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("자식"));
    }

    @Test
    void 부모가_사라진_뒤_복원하면_루트로_올라오고_그_사실을_알린다() throws Exception {
        long root = createPage(null, "루트");
        long child = createPage(root, "자식");
        deletePage(child, null);
        deletePage(root, null);
        mvc.perform(delete("/api/wiki/pages/" + root + "/purge").with(asUser(ADMIN, "Alice")))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/wiki/pages/" + child + "/restore").with(asUser(EDITOR, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reparentedToRoot").value(true));

        assertThat(pages.findById(child).orElseThrow().getParentId()).isNull();
    }

    @Test
    void 영구삭제는_ADMIN만_할_수_있고_리비전까지_지운다() throws Exception {
        long page = createPage(null, "보고서");
        deletePage(page, null);

        mvc.perform(delete("/api/wiki/pages/" + page + "/purge").with(asUser(EDITOR, "Bob")))
                .andExpect(status().isForbidden());
        assertThat(revisions.findByPageIdOrderByVersionDesc(page)).isNotEmpty();

        mvc.perform(delete("/api/wiki/pages/" + page + "/purge").with(asUser(ADMIN, "Alice")))
                .andExpect(status().isNoContent());
        assertThat(pages.findAnyById(page)).isEmpty();
        assertThat(revisions.findByPageIdOrderByVersionDesc(page)).isEmpty();
    }

    @Test
    void 휴지통_비우기는_ADMIN만_할_수_있다() throws Exception {
        deletePage(createPage(null, "하나"), null);
        deletePage(createPage(null, "둘"), null);

        mvc.perform(delete("/api/wiki/spaces/" + space.getId() + "/trash").with(asUser(EDITOR, "Bob")))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/wiki/spaces/" + space.getId() + "/trash").with(asUser(ADMIN, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(2));
        assertThat(pages.findTrashedRows(space.getId())).isEmpty();
    }

    /**
     * 제한된 부모 밑에서 버려진 문서가 휴지통에서 노출되면 W18 제한이 무의미해진다.
     * 권한 인덱스는 버려진 페이지의 조상 체인까지 봐야 한다.
     */
    @Test
    void 상속된_VIEW_제한은_휴지통_목록에도_적용된다() throws Exception {
        long secret = createPage(null, "비밀");
        long child = createPage(secret, "비밀 하위");
        restrictions.save(PageRestriction.of(secret, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, EDITOR, EDITOR));
        deletePage(child, null);

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/trash").with(asUser(EDITOR, "Bob")))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/trash").with(asUser(OUTSIDER, "Carol")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/wiki/pages/" + child + "/restore").with(asUser(OUTSIDER, "Carol")))
                .andExpect(status().isForbidden());
    }

    /**
     * 두 삭제가 같은 밀리초에 들어갈 수 있어 "지금"을 경계로 쓸 수 없다 —
     * 오래된 쪽의 deleted_at을 직접 과거로 밀어 경계를 확정한다.
     */
    @Test
    void 보존_기간이_지난_묶음만_자동으로_영구_삭제된다() throws Exception {
        long old = createPage(null, "오래된 것");
        deletePage(old, null);
        long fresh = createPage(null, "방금 버린 것");
        deletePage(fresh, null);

        Instant cutoff = Instant.now().minusSeconds(60);
        backdate(old, cutoff.minusSeconds(60));

        int purged = trash.purgeExpired(cutoff);

        assertThat(purged).isEqualTo(1);
        assertThat(pages.findAnyById(old)).isEmpty();
        assertThat(pages.findAnyById(fresh)).isPresent();
    }

    @Autowired JdbcTemplate jdbc;

    private void backdate(long pageId, Instant when) {
        jdbc.update("update page set deleted_at = ? where id = ?", java.sql.Timestamp.from(when), pageId);
    }
}
