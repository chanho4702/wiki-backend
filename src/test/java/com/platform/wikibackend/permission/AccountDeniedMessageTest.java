package com.platform.wikibackend.permission;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageComment;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import com.platform.wikibackend.security.AccountStatusInterceptor;
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
 * 403 문구 두 갈래 — org가 계정 상태를 사유로 주면 그 사실을 그대로 말하고(승인 대기·정지·비활성),
 * 권한만 모자란 거부는 <b>기존 문구를 그대로</b> 낸다(회귀).
 *
 * <p>왜 갈라야 하는가: "EDIT 권한이 필요합니다"를 본 승인 대기 사용자는 스페이스 관리자에게 권한을
 * 요청하지만, 실제로 필요한 건 조직 관리자의 계정 승인이다. 사유를 안 실으면 사람이 잘못된 조치를 한다.
 * 문구는 alm-backend와 같다(common-proto 0.16.0 {@code denied_reason}).
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountDeniedMessageTest {

    private static final long USER = 7001L;
    private static final long OTHER = 7002L;

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageCommentRepository comments;
    @Autowired FakePermissionClient perms;
    @Autowired FakeMemberDirectory directory;
    @Autowired AccountStatusInterceptor gate;

    MockMvc mvc;
    Space space;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        comments.deleteAllInBatch();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();
        // 계정 상태 게이트는 이 테스트의 대상이 아니다 — 디렉터리를 비워 두면 "org에 아직 없는 사람"으로 통과한다
        directory.reset();
        gate.evictAll();
        space = spaces.save(Space.of("dm" + (System.nanoTime() % 100000), "문구", null, USER));
    }

    // ── 스페이스 권한 가드(SpaceService.require) ──

    @Test
    void 권한만_모자라면_기존_문구를_그대로_낸다() throws Exception {
        mvc.perform(get("/api/wiki/spaces/{id}", space.getId()).with(asUser(USER, "Alice")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("VIEW 권한이 필요합니다 (space " + space.getId() + ")"));
    }

    @Test
    void 승인_대기_사유면_계정_문구로_바뀐다() throws Exception {
        perms.denyWithReason(USER, "PENDING");

        mvc.perform(get("/api/wiki/spaces/{id}", space.getId()).with(asUser(USER, "Alice")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("승인 대기 중인 계정입니다"));
    }

    @Test
    void 정지_사유면_계정_문구로_바뀐다() throws Exception {
        perms.denyWithReason(USER, "SUSPENDED");

        mvc.perform(get("/api/wiki/spaces/{id}", space.getId()).with(asUser(USER, "Alice")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("정지된 계정입니다"));
    }

    @Test
    void 비활성_사유면_계정_문구로_바뀐다() throws Exception {
        perms.denyWithReason(USER, "DEACTIVATED");

        mvc.perform(get("/api/wiki/spaces/{id}", space.getId()).with(asUser(USER, "Alice")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("비활성된 계정입니다"));
    }

    /** 권한 부족 사유(NO_GRANT 등)는 계정 문구가 아니다 — 기존 문구가 유지된다 */
    @Test
    void 권한_부족_사유는_기존_문구를_유지한다() throws Exception {
        perms.denyWithReason(USER, "NO_GRANT");

        mvc.perform(get("/api/wiki/spaces/{id}", space.getId()).with(asUser(USER, "Alice")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("VIEW 권한이 필요합니다 (space " + space.getId() + ")"));
    }

    // ── 감사 로그(AuditService.list) ──

    @Test
    void 감사_로그_거부는_기존_문구를_유지한다() throws Exception {
        perms.allow(USER, space.getId(), WikiAction.VIEW);

        mvc.perform(get("/api/wiki/spaces/{id}/audit", space.getId()).with(asUser(USER, "Alice")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("감사 로그는 스페이스 관리자만 볼 수 있습니다"));
    }

    @Test
    void 감사_로그도_계정_상태를_그대로_말한다() throws Exception {
        perms.allow(USER, space.getId(), WikiAction.VIEW);
        perms.denyWithReason(USER, "SUSPENDED");

        mvc.perform(get("/api/wiki/spaces/{id}/audit", space.getId()).with(asUser(USER, "Alice")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("정지된 계정입니다"));
    }

    // ── 작업 항목 토글(TaskService.setDone) ──

    @Test
    void 작업_토글_거부는_기존_문구를_유지한다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "할 일", "- [ ] 하나", USER));
        perms.allow(USER, space.getId(), WikiAction.VIEW);

        mvc.perform(put("/api/wiki/pages/{pageId}/tasks/{lineNo}", page.getId(), 1)
                        .with(asUser(USER, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("EDIT 권한이 필요합니다 (space " + space.getId() + ")"));
    }

    @Test
    void 작업_토글도_계정_상태를_그대로_말한다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "할 일", "- [ ] 하나", USER));
        perms.allow(USER, space.getId(), WikiAction.VIEW);
        perms.denyWithReason(USER, "DEACTIVATED");

        mvc.perform(put("/api/wiki/pages/{pageId}/tasks/{lineNo}", page.getId(), 1)
                        .with(asUser(USER, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"done\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("비활성된 계정입니다"));
    }

    // ── 남의 코멘트 삭제(CommentService.delete) ──

    @Test
    void 남의_코멘트_삭제_거부는_기존_문구를_유지한다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "문서", "본문", OTHER));
        PageComment comment = comments.save(PageComment.of(page.getId(), null, OTHER, "Bob", "남의 코멘트"));
        perms.allow(USER, space.getId(), WikiAction.VIEW);

        mvc.perform(delete("/api/wiki/comments/{id}", comment.getId()).with(asUser(USER, "Alice")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("본인의 코멘트만 삭제할 수 있습니다"));
    }

    @Test
    void 남의_코멘트_삭제도_계정_상태를_그대로_말한다() throws Exception {
        Page page = pages.save(Page.of(space.getId(), null, "문서", "본문", OTHER));
        PageComment comment = comments.save(PageComment.of(page.getId(), null, OTHER, "Bob", "남의 코멘트"));
        perms.allow(USER, space.getId(), WikiAction.VIEW);
        perms.denyWithReason(USER, "PENDING");

        mvc.perform(delete("/api/wiki/comments/{id}", comment.getId()).with(asUser(USER, "Alice")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("승인 대기 중인 계정입니다"));
    }
}
