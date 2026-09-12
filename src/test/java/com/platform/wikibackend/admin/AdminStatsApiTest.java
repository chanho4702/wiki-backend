package com.platform.wikibackend.admin;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.attachment.AttachmentLifecycleStatus;
import com.platform.wikibackend.attachment.StorageBackend;
import com.platform.wikibackend.attachment.StoredObject;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageComment;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 대시보드 현황(설계 §4.1).
 *
 * 세 가지를 지킨다 — 숫자가 실제 데이터와 맞는가, 전역 관리자가 아니면 막히는가,
 * 60초 캐시가 실제로 DB를 다시 때리지 않는가.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminStatsApiTest {

    private static final long ADMIN = 1L;
    private static final long MEMBER = 2L;
    private static final String PATH = "/api/wiki/admin/stats";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired AttachmentRepository attachments;
    @Autowired PageCommentRepository comments;
    @Autowired FakePermissionClient perms;
    @Autowired AdminStatsService service;

    MockMvc mvc;
    Space space;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        comments.deleteAllInBatch();
        attachments.deleteAllInBatch();
        revisions.deleteAllInBatch();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        perms.reset();
        // 전역 관리자 = CheckPermission(GLOBAL, ADMIN) — 감사 로그와 같은 판정이다.
        perms.allowAll(ADMIN);
        // 캐시는 전역이라 앞선 테스트의 값이 남는다. 테스트 사이에는 비운다.
        service.invalidate();

        space = spaces.save(Space.of("ad" + (System.nanoTime() % 100000), "현황", null, ADMIN));
    }

    @Test
    void 전역_관리자는_현황을_읽는다() throws Exception {
        Page published = pages.save(Page.of(space.getId(), null, "공개 문서", "본문", ADMIN));
        pages.save(Page.of(space.getId(), null, "초안", "본문", ADMIN, PageType.PAGE, PageStatus.DRAFT));
        Page trashed = pages.save(Page.of(space.getId(), null, "버린 문서", "본문", ADMIN));

        revisions.save(PageRevision.snapshotOf(published));
        // 30일 전 편집 — 7일 창 밖이라 editsLast7Days에 들어가면 안 된다.
        // @CreationTimestamp가 INSERT에서 "지금"으로 덮으므로 저장 뒤 SQL로 되돌린다.
        PageRevision old = revisions.save(PageRevision.imported(
                published.getId(), 2, "공개 문서", "옛 본문", ADMIN, "Alice", null));
        jdbc.update("update page_revision set created_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), old.getId());

        attachments.save(Attachment.of(published.getId(), "a.png", "image/png", 1000L,
                new StoredObject(StorageBackend.LOCAL, null, "key-a", null), null, ADMIN));
        // 확정되지 않은 임시 업로드는 세지 않는다.
        attachments.save(Attachment.of(published.getId(), "b.png", "image/png", 9_000_000L,
                new StoredObject(StorageBackend.LOCAL, null, "key-b", null), null, ADMIN,
                AttachmentLifecycleStatus.PENDING));

        comments.save(PageComment.of(published.getId(), null, ADMIN, "Alice", "댓글"));

        // 휴지통 문서는 pages에서 빠지고 trashedPages로 센다.
        mvc.perform(delete("/api/wiki/pages/{id}", trashed.getId()).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isNoContent());
        service.invalidate();

        mvc.perform(get(PATH).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaces").value(1))
                .andExpect(jsonPath("$.pages").value(2))
                .andExpect(jsonPath("$.draftPages").value(1))
                .andExpect(jsonPath("$.trashedPages").value(1))
                .andExpect(jsonPath("$.revisions").value(2))
                .andExpect(jsonPath("$.attachments").value(1))
                .andExpect(jsonPath("$.attachmentBytes").value(1000))
                .andExpect(jsonPath("$.editsLast7Days").value(1))
                .andExpect(jsonPath("$.comments").value(1));
    }

    /** 첨부가 하나도 없을 때 SUM은 null이다 — coalesce가 빠지면 여기서 깨진다. */
    @Test
    void 데이터가_없으면_전부_0이다() throws Exception {
        spaces.deleteAllInBatch();
        service.invalidate();

        mvc.perform(get(PATH).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaces").value(0))
                .andExpect(jsonPath("$.pages").value(0))
                .andExpect(jsonPath("$.attachments").value(0))
                .andExpect(jsonPath("$.attachmentBytes").value(0))
                .andExpect(jsonPath("$.editsLast7Days").value(0));
    }

    /**
     * 스페이스 하나의 ADMIN으로는 볼 수 없다 — 전 스페이스를 가로지르는 숫자이기 때문이다.
     * 오류 본문은 플랫폼 공통 계약({"error": …}) 그대로다.
     */
    @Test
    void 전역_관리자가_아니면_403이다() throws Exception {
        for (com.platform.wikibackend.permission.WikiAction action
                : com.platform.wikibackend.permission.WikiAction.values()) {
            perms.allow(MEMBER, space.getId(), action);
        }

        mvc.perform(get(PATH).with(asUser(MEMBER, "Bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    /** 권한 판정은 캐시 앞에서 돈다 — 관리자가 먼저 채운 캐시를 비관리자가 받아 가면 안 된다. */
    @Test
    void 캐시가_채워져도_비관리자는_막힌다() throws Exception {
        mvc.perform(get(PATH).with(asUser(ADMIN, "Alice"))).andExpect(status().isOk());

        mvc.perform(get(PATH).with(asUser(MEMBER, "Bob")))
                .andExpect(status().isForbidden());
    }

    /**
     * 60초 캐시. 사이에 문서를 하나 더 넣어도 두 번째 응답이 그대로면 DB를 다시 세지 않은 것이다.
     * 무효화하면 새 숫자가 나와야 한다 — 캐시가 아니라 응답이 굳어 버린 것이 아님을 함께 본다.
     */
    @Test
    void 같은_숫자를_60초_동안_돌려준다() throws Exception {
        pages.save(Page.of(space.getId(), null, "첫 문서", "본문", ADMIN));

        mvc.perform(get(PATH).with(asUser(ADMIN, "Alice")))
                .andExpect(jsonPath("$.pages").value(1));

        pages.save(Page.of(space.getId(), null, "둘째 문서", "본문", ADMIN));

        mvc.perform(get(PATH).with(asUser(ADMIN, "Alice")))
                .andExpect(jsonPath("$.pages").value(1));

        service.invalidate();

        mvc.perform(get(PATH).with(asUser(ADMIN, "Alice")))
                .andExpect(jsonPath("$.pages").value(2));
    }
}
