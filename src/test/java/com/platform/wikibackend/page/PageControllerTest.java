package com.platform.wikibackend.page;

import com.platform.wikibackend.domain.Page;
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

import com.platform.wikibackend.TestPages;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class PageControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired RecordingEventPublisher events;
    MockMvc mvc;

    Space space;
    static final long EDITOR = 1L;
    static final long VIEWER = 2L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        events.reset();
        space = spaces.save(Space.of("dev", "개발", null, EDITOR));
        perms.allow(EDITOR, space.getId(), WikiAction.VIEW);
        perms.allow(EDITOR, space.getId(), WikiAction.EDIT);
        perms.allow(VIEWER, space.getId(), WikiAction.VIEW);
    }

    private long createPage(Long parentId, String title) throws Exception {
        String parent = parentId == null ? "null" : String.valueOf(parentId);
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":" + parent
                                + ",\"title\":\"" + title + "\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
    }

    @Test
    void 생성하면_버전1_리비전이_생기고_이벤트가_발행된다() throws Exception {
        long id = createPage(null, "루트");

        assertThat(revisions.findByPageIdAndVersion(id, 1)).isPresent();
        assertThat(events.events).anyMatch(e -> e.hasPageCreated());
    }

    @Test
    void VIEW만_있는_사용자는_생성_불가_403_조회는_가능() throws Exception {
        long id = createPage(null, "루트");

        mvc.perform(post("/api/wiki/pages").with(asUser(VIEWER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":null,\"title\":\"t\",\"content\":\"c\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/wiki/pages/" + id).with(asUser(VIEWER, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("본문"));
    }

    @Test
    void 트리_목록은_본문_없이_id_parent_title만() throws Exception {
        long root = createPage(null, "루트");
        createPage(root, "자식");

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/children")
                        .param("parentId", String.valueOf(root)).with(asUser(VIEWER, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                // (int) 캐스팅: jsonPath는 작은 수를 Integer로 역직렬화 — Long 비교는 실패한다(알려진 함정)
                .andExpect(jsonPath("$[0].parentId").value((int) root))
                .andExpect(jsonPath("$[0].content").doesNotExist());
    }

    @Test
    void 다른_스페이스의_부모는_400() throws Exception {
        Space other = spaces.save(Space.of("ops", "운영", null, EDITOR));
        perms.allow(EDITOR, other.getId(), WikiAction.EDIT);
        long root = createPage(null, "루트");

        mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + other.getId() + ",\"parentId\":" + root + ",\"title\":\"t\",\"content\":\"c\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * W21-1 소프트 삭제 — 하위는 조회에서 사라지지만 리비전은 남는다(복원 재료).
     * 색인은 즉시 내려야 하므로 pageDeleted 이벤트는 그대로 발행한다.
     */
    @Test
    void children_cascade면_하위가_함께_휴지통으로_가고_리비전은_남는다() throws Exception {
        long root = createPage(null, "루트");
        long child = createPage(root, "자식");

        mvc.perform(delete("/api/wiki/pages/" + root + "?children=cascade").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(pages.findById(child)).isEmpty();
        assertThat(pages.findById(root)).isEmpty();
        assertThat(pages.findAnyById(child)).isPresent();
        assertThat(revisions.findByPageIdOrderByVersionDesc(root)).isNotEmpty();
        assertThat(revisions.findByPageIdOrderByVersionDesc(child)).isNotEmpty();
        assertThat(events.events).anyMatch(e -> e.hasPageDeleted());
    }

    @Test
    void 잎_페이지는_children_옵션_없이도_삭제된다() throws Exception {
        long leaf = createPage(null, "잎");

        mvc.perform(delete("/api/wiki/pages/" + leaf).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(pages.findById(leaf)).isEmpty();
    }

    /**
     * 옵션 없는 삭제가 하위를 통째로 날리면 호출 실수 한 번이 문서 트리를 지운다.
     * 프론트 목업 계약("하위가 있으면 거부")과도 어긋나 모드에 따라 결과가 달라진다.
     */
    @Test
    void 하위가_있는데_children_옵션이_없으면_409로_거부한다() throws Exception {
        long root = createPage(null, "루트");
        long child = createPage(root, "자식");

        mvc.perform(delete("/api/wiki/pages/" + root).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("하위 페이지가 있어 삭제할 수 없습니다"));

        assertThat(pages.findById(root)).isPresent();
        assertThat(pages.findById(child)).isPresent();
    }

    @Test
    void children_promote면_자식이_조부모로_올라가고_대상만_삭제된다() throws Exception {
        long root = createPage(null, "루트");
        long mid = createPage(root, "중간");
        long leaf = createPage(mid, "잎");

        mvc.perform(delete("/api/wiki/pages/" + mid + "?children=promote").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(pages.findById(mid)).isEmpty();
        assertThat(pages.findById(leaf)).isPresent();
        assertThat(pages.findById(leaf).orElseThrow().getParentId()).isEqualTo(root);
        // 승격된 자식의 이력은 남는다 — 옮겨졌을 뿐 지워진 게 아니다
        assertThat(revisions.findByPageIdOrderByVersionDesc(leaf)).isNotEmpty();
        // 버린 페이지의 이력도 남는다(W21-1 소프트 삭제) — 복원하면 그대로 돌아와야 한다
        assertThat(revisions.findByPageIdOrderByVersionDesc(mid)).isNotEmpty();
        assertThat(pages.findAnyById(mid)).isPresent();
    }

    @Test
    void 루트를_promote로_지우면_자식이_루트가_된다() throws Exception {
        long root = createPage(null, "루트");
        long child = createPage(root, "자식");

        mvc.perform(delete("/api/wiki/pages/" + root + "?children=promote").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(pages.findById(child).orElseThrow().getParentId()).isNull();
    }

    /** 색인이 스테일해지지 않으려면 부모가 바뀐 사실이 이벤트로 나가야 한다. */
    @Test
    void promote로_옮겨진_자식은_pageUpdated_이벤트를_낸다() throws Exception {
        long root = createPage(null, "루트");
        long mid = createPage(root, "중간");
        createPage(mid, "잎");
        events.reset();

        mvc.perform(delete("/api/wiki/pages/" + mid + "?children=promote").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(events.events).anyMatch(e -> e.hasPageUpdated());
        assertThat(events.events).anyMatch(e -> e.hasPageDeleted());
    }

    /**
     * parent_id 순환은 정상 경로로는 안 생기지만(validateParent가 막는다) 데이터가 손상되면 가능하다.
     * 가드가 없으면 재귀 삭제·조상 순회가 무한 루프에 빠져 스레드를 잡아먹는다.
     */
    @Test
    void 순환하는_손상_데이터에서도_삭제가_끝난다() throws Exception {
        long a = createPage(null, "A");
        long b = createPage(a, "B");
        Page pa = pages.findById(a).orElseThrow();
        pa.moveTo(b); // A → B → A 순환
        pages.saveAndFlush(pa);

        mvc.perform(delete("/api/wiki/pages/" + a + "?children=cascade").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(pages.findById(a)).isEmpty();
    }

    // ── 콘텐츠 타입(folder) · 게시 상태(draft) — 프론트 기획 P1/P3의 백엔드 계약 ──

    @Test
    void 생성_기본값은_page_published다() throws Exception {
        long id = createPage(null, "기본");

        mvc.perform(get("/api/wiki/pages/" + id).with(asUser(EDITOR, "Alice")))
                .andExpect(jsonPath("$.type").value("page"))
                .andExpect(jsonPath("$.status").value("published"));
    }

    @Test
    void 초안_페이지는_응답과_트리에_draft로_실린다() throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":null,\"title\":\"초안\""
                                + ",\"content\":\"\",\"status\":\"draft\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("page"))
                .andExpect(jsonPath("$.status").value("draft"))
                .andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);

        // 트리가 폴더 아이콘·초안 배지를 그리려면 두 값이 목록에도 실려야 한다
        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages/children").with(asUser(VIEWER, "Bob")))
                .andExpect(jsonPath("$[0].id").value((int) id))
                .andExpect(jsonPath("$[0].type").value("page"))
                .andExpect(jsonPath("$[0].status").value("draft"));
    }

    /** 폴더는 게시 개념이 없다(기획 P3) — draft로 만들어달라고 해도 published로 고정한다. */
    @Test
    void 폴더는_초안으로_요청해도_published로_만들어진다() throws Exception {
        mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":null,\"title\":\"폴더\""
                                + ",\"content\":\"\",\"type\":\"folder\",\"status\":\"draft\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("folder"))
                .andExpect(jsonPath("$.status").value("published"));
    }

    @Test
    void 초안을_게시하면_published가_되고_다시_게시해도_같다() throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"parentId\":null,\"title\":\"초안\""
                                + ",\"content\":\"\",\"status\":\"draft\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);
        int versionBefore = com.jayway.jsonpath.JsonPath.parse(body).read("$.version", Integer.class);

        mvc.perform(post("/api/wiki/pages/" + id + "/publish").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"))
                // 게시는 내용 변경이 아니다 — 버전을 올리지 않는다(프론트 목업 계약과 동일)
                .andExpect(jsonPath("$.version").value(versionBefore));

        mvc.perform(post("/api/wiki/pages/" + id + "/publish").with(asUser(EDITOR, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"));
    }

    @Test
    void VIEW만_있는_사용자는_게시할_수_없다() throws Exception {
        long id = createPage(null, "초안");

        mvc.perform(post("/api/wiki/pages/" + id + "/publish").with(asUser(VIEWER, "Bob")))
                .andExpect(status().isForbidden());
    }
}
