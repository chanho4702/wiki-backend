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
    @Autowired RecordingEventPublisher events;
    MockMvc mvc;

    Space space;
    static final long EDITOR = 1L;
    static final long VIEWER = 2L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        pages.deleteAll();
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

        mvc.perform(get("/api/wiki/spaces/" + space.getId() + "/pages").with(asUser(VIEWER, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // (int) 캐스팅: jsonPath는 작은 수를 Integer로 역직렬화 — Long 비교는 실패한다(알려진 함정)
                .andExpect(jsonPath("$[1].parentId").value((int) root))
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

    @Test
    void 삭제하면_하위와_리비전이_함께_사라지고_이벤트가_발행된다() throws Exception {
        long root = createPage(null, "루트");
        long child = createPage(root, "자식");

        mvc.perform(delete("/api/wiki/pages/" + root).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isNoContent());

        assertThat(pages.findById(child)).isEmpty();
        assertThat(events.events).anyMatch(e -> e.hasPageDeleted());
    }
}
