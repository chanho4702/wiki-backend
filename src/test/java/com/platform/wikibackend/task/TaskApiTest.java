package com.platform.wikibackend.task;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.PageTaskRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 액션 아이템(W23). 체크박스 목록은 있었지만 "누가 언제까지"가 없어서 회의록의 할 일이 회의록
 * 안에서만 살았다. 담당자는 멘션, 기한은 날짜 요소 — 새 문법은 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
class TaskApiTest {

    private static final long ME = 1L;
    private static final long YOU = 2L;

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageTaskRepository tasks;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient perms;

    MockMvc mvc;
    Long spaceId;

    private static final String BODY = String.join("\n",
            "# 회의록",
            "",
            "- [ ] 배포 공지 [@나](user:1) [2026-09-01](date:2026-09-01)",
            "- [x] 회의실 예약 [@나](user:1)",
            "- [ ] 문서 정리 [@너](user:2)",
            "- [ ] 담당자 없음");

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        tasks.deleteAllInBatch();
        revisions.deleteAllInBatch();
        restrictions.deleteAllInBatch();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();
        spaceId = spaces.save(Space.of("tk" + (System.nanoTime() % 100000), "작업", null, ME)).getId();
        for (long u : new long[] {ME, YOU}) {
            perms.allow(u, spaceId, WikiAction.VIEW);
            perms.allow(u, spaceId, WikiAction.EDIT);
        }
    }

    private long createPage(String body) throws Exception {
        String res = mvc.perform(post("/api/wiki/pages").with(asUser(ME, "Me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                                java.util.Map.of("spaceId", spaceId, "title", "회의록", "content", body))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(res).get("id").asLong();
    }

    @Test
    void 저장하면_본문의_체크박스가_작업이_된다() throws Exception {
        long pageId = createPage(BODY);

        var rows = tasks.findByPageIdOrderByLineNoAsc(pageId);
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).getLineNo()).isEqualTo(3);
        assertThat(rows.get(0).getAssigneeId()).isEqualTo(ME);
        assertThat(rows.get(0).getDueDate()).isEqualTo(java.time.LocalDate.parse("2026-09-01"));
        assertThat(rows.get(0).getText()).isEqualTo("배포 공지 @나 2026-09-01");
        assertThat(rows.get(1).isDone()).isTrue();
        assertThat(rows.get(3).getAssigneeId()).isNull();
    }

    /** 기한이 있는 것이 먼저(임박한 순), 완료는 따로 — 내 몫만 본다. */
    @Test
    void 내_작업은_담당자가_나인_미완료_항목이다() throws Exception {
        createPage(BODY);

        mvc.perform(get("/api/wiki/tasks/mine").with(asUser(ME, "Me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].text").value("배포 공지 @나 2026-09-01"))
                .andExpect(jsonPath("$[0].dueDate").value("2026-09-01"))
                .andExpect(jsonPath("$[0].pageTitle").value("회의록"));

        mvc.perform(get("/api/wiki/tasks/mine?done=true").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].text").value("회의실 예약 @나"));
    }

    /** 토글은 편집이다 — 본문의 그 줄이 바뀌고 리비전이 남는다. */
    @Test
    void 체크하면_본문이_바뀌고_리비전이_남는다() throws Exception {
        long pageId = createPage(BODY);
        int before = revisions.findByPageIdOrderByVersionDesc(pageId).size();

        mvc.perform(put("/api/wiki/pages/{id}/tasks/{line}", pageId, 3).with(asUser(ME, "Me"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true));

        Page page = pages.findById(pageId).orElseThrow();
        assertThat(page.getContent().split("\n")[2]).startsWith("- [x] 배포 공지");
        assertThat(page.getVersion()).isEqualTo(2);
        assertThat(revisions.findByPageIdOrderByVersionDesc(pageId)).hasSize(before + 1);
        mvc.perform(get("/api/wiki/tasks/mine").with(asUser(ME, "Me")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 동시 편집으로 줄이 밀렸으면 엉뚱한 줄을 건드리지 않는다. */
    @Test
    void 작업_항목이_아닌_줄은_토글할_수_없다() throws Exception {
        long pageId = createPage(BODY);

        mvc.perform(put("/api/wiki/pages/{id}/tasks/{line}", pageId, 1).with(asUser(ME, "Me"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"done\":true}"))
                .andExpect(status().isConflict());
    }

    /** 문서가 잠긴 뒤에도 항목 텍스트가 남으면 그것만으로 샌다 — 읽을 때마다 다시 거른다. */
    @Test
    void 볼_수_없게_된_문서의_작업은_목록에서_빠진다() throws Exception {
        long pageId = createPage("- [ ] 비밀 [@너](user:2)");
        restrictions.save(PageRestriction.of(pageId, PageRestriction.Type.VIEW,
                PageRestriction.PrincipalType.USER, ME, ME));

        mvc.perform(get("/api/wiki/tasks/mine").with(asUser(YOU, "You")))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
