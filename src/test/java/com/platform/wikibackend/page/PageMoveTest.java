package com.platform.wikibackend.page;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 트리 이동/재정렬(P1-001) 계약 — 형제 순서가 서버에 영속되고, 이동은 버전을 올리지 않는다.
 * 프론트 목업(wikiMock.movePage)과 같은 의미론: beforeId 앞(없으면 맨 뒤), 순환 거부, 1..n 조밀.
 */
@SpringBootTest
@ActiveProfiles("test")
class PageMoveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long EDITOR = 1L;
    private static final long VIEWER = 2L;

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired FakePermissionClient perms;

    private Long spaceId;
    private Page a;
    private Page b;
    private Page c;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        revisions.deleteAllInBatch();
        pages.deleteAllInBatch();
        spaces.deleteAllInBatch();
        perms.reset();
        spaceId = spaces.save(Space.of("mv" + (System.nanoTime() % 100000), "이동", null, EDITOR)).getId();
        perms.allow(EDITOR, spaceId, WikiAction.VIEW);
        perms.allow(EDITOR, spaceId, WikiAction.EDIT);
        perms.allow(VIEWER, spaceId, WikiAction.VIEW);
        a = savePage(null, "A", 1);
        b = savePage(null, "B", 2);
        c = savePage(null, "C", 3);
    }

    private Page savePage(Long parentId, String title, long order) {
        Page page = pages.save(Page.of(spaceId, parentId, title, "", EDITOR));
        page.resequence(order);
        return pages.saveAndFlush(page);
    }

    @Test
    void 형제_재정렬이_서버에_영속되고_버전은_그대로다() throws Exception {
        // C를 A 앞으로
        mvc.perform(post("/api/wiki/pages/{id}/move", c.getId()).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null,\"beforeId\":" + a.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.version").value(1)); // 이동은 편집이 아니다

        assertThat(pages.findSiblings(spaceId, null).stream().map(Page::getTitle))
                .containsExactly("C", "A", "B");
        // 리비전도 쌓이지 않는다(스토어 계약: movePage 스냅샷 없음)
        assertThat(revisions.findByPageIdOrderByVersionDesc(c.getId())).isEmpty();
    }

    @Test
    void 하위로_이동하면_두_그룹_모두_1부터_조밀하다() throws Exception {
        mvc.perform(post("/api/wiki/pages/{id}/move", b.getId()).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + a.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(a.getId()))
                .andExpect(jsonPath("$.position").value(1));

        assertThat(pages.findSiblings(spaceId, null).stream().map(p -> p.getTitle() + p.getSortOrder()))
                .containsExactly("A1", "C2");
        assertThat(pages.findSiblings(spaceId, a.getId()).stream().map(p -> p.getTitle() + p.getSortOrder()))
                .containsExactly("B1");
    }

    @Test
    void 트리_응답이_position을_실어_재접속에도_순서가_유지된다() throws Exception {
        mvc.perform(post("/api/wiki/pages/{id}/move", c.getId()).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null,\"beforeId\":" + b.getId() + "}"))
                .andExpect(status().isOk());

        String body = mvc.perform(get("/api/wiki/spaces/{id}/pages", spaceId).with(asUser(VIEWER, "Bob")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var items = JSON.readTree(body);
        // position 오름차순으로 정렬하면 A, C, B
        record Row(String title, long position) {}
        var rows = new java.util.ArrayList<Row>();
        items.forEach((node) -> rows.add(new Row(node.get("title").asText(), node.get("position").asLong())));
        rows.sort(java.util.Comparator.comparingLong(Row::position));
        assertThat(rows.stream().map(Row::title)).containsExactly("A", "C", "B");
    }

    @Test
    void 자기_자손_밑으로는_이동할_수_없다() throws Exception {
        mvc.perform(post("/api/wiki/pages/{id}/move", b.getId()).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + a.getId() + "}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/wiki/pages/{id}/move", a.getId()).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + b.getId() + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 없는_beforeId는_조용히_맨_뒤다() throws Exception {
        mvc.perform(post("/api/wiki/pages/{id}/move", a.getId()).with(asUser(EDITOR, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null,\"beforeId\":999999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(3));
    }

    @Test
    void EDIT_권한이_없으면_이동할_수_없다() throws Exception {
        mvc.perform(post("/api/wiki/pages/{id}/move", a.getId()).with(asUser(VIEWER, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null}"))
                .andExpect(status().isForbidden());
    }
}
