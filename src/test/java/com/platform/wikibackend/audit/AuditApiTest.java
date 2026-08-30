package com.platform.wikibackend.audit;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AuditLogRepository;
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

import static com.platform.wikibackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 감사 로그(W23).
 *
 * "누가 이 문서를 지웠나", "언제부터 이 페이지가 잠겼나"를 확인할 방법이 없었다. 되돌리기
 * 어렵거나 접근 범위를 바꾸는 조작만 남긴다 — 본문 수정은 리비전이 이미 남긴다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditApiTest {

    private static final long ADMIN = 1L;
    private static final long EDITOR = 2L;

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired AuditLogRepository logs;
    @Autowired FakePermissionClient perms;

    MockMvc mvc;
    Space space;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        logs.deleteAllInBatch();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();

        space = spaces.save(Space.of("au" + (System.nanoTime() % 100000), "감사", null, ADMIN));
        for (WikiAction action : WikiAction.values()) perms.allow(ADMIN, space.getId(), action);
        perms.allow(EDITOR, space.getId(), WikiAction.VIEW);
        perms.allow(EDITOR, space.getId(), WikiAction.EDIT);
    }

    /**
     * 지운 문서의 제목이 기록에 남아야 한다 — id만 남기면 숫자만 보이고 아무도 못 알아본다.
     * 그래서 대상 이름을 함께 저장한다.
     */
    @Test
    void 페이지를_지우면_제목과_함께_기록된다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "지울 문서", "본문", ADMIN));

        mvc.perform(delete("/api/wiki/pages/{id}", page.getId()).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/wiki/spaces/{id}/audit", space.getId()).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("PAGE_TRASHED"))
                .andExpect(jsonPath("$[0].targetLabel").value("지울 문서"))
                .andExpect(jsonPath("$[0].actorId").value(ADMIN));
    }

    @Test
    void 제한을_바꾸면_기록된다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "잠글 문서", "본문", ADMIN));

        mvc.perform(put("/api/wiki/pages/{id}/restrictions", page.getId())
                        .with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"view\":[{\"type\":\"USER\",\"id\":1}],\"edit\":[]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/wiki/spaces/{id}/audit", space.getId()).with(asUser(ADMIN, "Alice")))
                .andExpect(jsonPath("$[0].action").value("PAGE_RESTRICTIONS_CHANGED"))
                .andExpect(jsonPath("$[0].targetLabel").value("잠글 문서"));
    }

    @Test
    void 스페이스_이름을_바꾸면_이전_이름이_남는다() throws Exception {
        mvc.perform(put("/api/wiki/spaces/{id}", space.getId())
                        .with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"감사 2팀\",\"description\":null}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/wiki/spaces/{id}/audit", space.getId()).with(asUser(ADMIN, "Alice")))
                .andExpect(jsonPath("$[0].action").value("SPACE_UPDATED"))
                .andExpect(jsonPath("$[0].detail").value("이름 변경: 감사"));
    }

    /** 누가 무엇을 지웠는지는 그 스페이스를 볼 수 있는 모두가 알아야 할 정보가 아니다. */
    @Test
    void 관리자가_아니면_감사_로그를_볼_수_없다() throws Exception {
        mvc.perform(get("/api/wiki/spaces/{id}/audit", space.getId()).with(asUser(EDITOR, "Bob")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 최신_기록이_먼저_온다() throws Exception {
        Page first = pages.save(Page.of(space.getId(), null, "먼저", "본문", ADMIN));
        Page second = pages.save(Page.of(space.getId(), null, "나중", "본문", ADMIN));
        mvc.perform(delete("/api/wiki/pages/{id}", first.getId()).with(asUser(ADMIN, "Alice")));
        mvc.perform(delete("/api/wiki/pages/{id}", second.getId()).with(asUser(ADMIN, "Alice")));

        mvc.perform(get("/api/wiki/spaces/{id}/audit", space.getId()).with(asUser(ADMIN, "Alice")))
                .andExpect(jsonPath("$[0].targetLabel").value("나중"))
                .andExpect(jsonPath("$[1].targetLabel").value("먼저"));
    }

    /** 본문 수정은 리비전이 이미 남긴다 — 감사 로그까지 채우면 정작 볼 것이 묻힌다. */
    @Test
    void 본문_수정은_감사_로그에_남지_않는다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "고칠 문서", "본문", ADMIN));

        mvc.perform(put("/api/wiki/pages/{id}", page.getId())
                        .with(asUser(ADMIN, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"고칠 문서\",\"content\":\"고친 본문\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/wiki/spaces/{id}/audit", space.getId()).with(asUser(ADMIN, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
