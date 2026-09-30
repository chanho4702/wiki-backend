package com.platform.wikibackend.collaboration;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.CollaborationDraftMetadata;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.importapi.ImportedPageWriter;
import com.platform.wikibackend.page.PageService;
import com.platform.wikibackend.page.dto.CollaborationDraftCommitRequest;
import com.platform.wikibackend.page.dto.PageUpdateRequest;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.CollaborationDraftMetadataRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import com.platform.wikibackend.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 차단-1: 게시가 아닌 경로가 버전을 올리면 공동 초안이 같은 트랜잭션에서 제자리 리셋되고,
 * 커밋 뒤에만 리셋 신호가 나간다.
 *
 * H2(create-drop)에는 엔티티가 매핑하지 않는 state/version/updated_at 열이 없다 — 운영 스키마(V5)와
 * 같은 열을 여기서 덧붙여 리셋 UPDATE가 실제로 그 열들을 쓰는지 본다. Postgres 쪽 정합은
 * {@code FlywaySchemaValidationTest}가 따로 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class CollaborationDraftResetTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired CollaborationDraftMetadataRepository drafts;
    @Autowired FakePermissionClient permissions;
    @Autowired RecordingCollaborationResetSignal signal;
    @Autowired PageService pageService;
    @Autowired TaskService taskService;
    @Autowired ImportedPageWriter importWriter;
    @Autowired PlatformTransactionManager txManager;

    MockMvc mvc;
    Space space;
    Page page;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        addServiceOwnedColumns(jdbc);
        drafts.deleteAll();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        permissions.reset();
        signal.reset();

        space = spaces.save(Space.of("reset", "리셋", null, 1L));
        permissions.allow(1L, space.getId(), WikiAction.EDIT);
        page = pages.save(Page.of(space.getId(), null, "제목", "- [ ] 할 일\n본문", 1L));
        revisions.save(PageRevision.snapshotOf(page));
    }

    /** collaboration-service 소유 열(V5). H2에서만 덧붙인다 — IF NOT EXISTS라 컨텍스트 재사용에도 안전하다. */
    static void addServiceOwnedColumns(JdbcTemplate jdbc) {
        jdbc.execute("alter table collaboration_document add column if not exists state bytea");
        jdbc.execute("alter table collaboration_document add column if not exists version bigint default 1");
        jdbc.execute("alter table collaboration_document add column if not exists updated_at"
                + " timestamp with time zone default now()");
    }

    /** 리셋 전 epoch. generation과 다른 값으로 두어 두 카운터를 섞어 쓰면 단언이 갈라지게 한다. */
    static final long SEEDED_EPOCH = 5L;

    private void seedDraft(long base, long generation) {
        drafts.save(CollaborationDraftMetadata.of(page.getId(), base, generation));
        jdbc.update("update collaboration_document set state = ?, version = 7, reset_epoch = ? where room = ?",
                new byte[] {1, 2, 3, 4}, SEEDED_EPOCH, room());
    }

    private String room() {
        return "page:" + page.getId();
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("select base_page_version, generation, reset_pending, reset_epoch, state, version"
                + " from collaboration_document where room = ?", room());
    }

    private void assertReset(long expectedBase, long expectedGeneration) {
        Map<String, Object> row = row();
        assertThat(((Number) row.get("base_page_version")).longValue()).isEqualTo(expectedBase);
        assertThat(((Number) row.get("generation")).longValue()).isEqualTo(expectedGeneration);
        assertThat(row.get("reset_pending")).isEqualTo(true);
        // 옛 Y.Doc은 버려지고 빈 update(2바이트)만 남는다 — NOT NULL용이며 로드는 거부된다
        assertThat((byte[]) row.get("state")).containsExactly(0, 0);
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(8L);
        // 세대 가드용 카운터는 리셋에서만 +1 — 신호도 generation이 아니라 이 값을 싣는다
        assertThat(((Number) row.get("reset_epoch")).longValue()).isEqualTo(SEEDED_EPOCH + 1);
        assertThat(signal.sent).containsExactly(
                new RecordingCollaborationResetSignal.Sent(room(), SEEDED_EPOCH + 1));
    }

    @Test
    void 일반_저장은_공동_초안을_새_버전_기준으로_리셋하고_커밋_후_신호를_보낸다() throws Exception {
        seedDraft(1, 1);

        mvc.perform(put("/api/wiki/pages/" + page.getId())
                        .with(asUser(1L, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"새 제목","content":"새 본문","expectedVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        assertReset(2, 2);
    }

    @Test
    void 초안이_없으면_리셋은_아무것도_하지_않는다() throws Exception {
        mvc.perform(put("/api/wiki/pages/" + page.getId())
                        .with(asUser(1L, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"새 제목","content":"새 본문","expectedVersion":1}
                                """))
                .andExpect(status().isOk());

        assertThat(drafts.count()).isZero();
        assertThat(signal.sent).isEmpty();
    }

    @Test
    void 저장이_롤백되면_초안도_그대로이고_신호도_나가지_않는다() {
        seedDraft(1, 1);

        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            pageService.update(1L, page.getId(),
                    new PageUpdateRequest("새 제목", "새 본문", null, 1, null));
            tx.setRollbackOnly();
        });

        Map<String, Object> row = row();
        assertThat(((Number) row.get("base_page_version")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("generation")).longValue()).isEqualTo(1L);
        assertThat(row.get("reset_pending")).isEqualTo(false);
        assertThat(((Number) row.get("reset_epoch")).longValue()).isEqualTo(SEEDED_EPOCH);
        assertThat((byte[]) row.get("state")).containsExactly(1, 2, 3, 4);
        assertThat(signal.sent).isEmpty();
    }

    @Test
    void 리셋이_거듭되면_epoch도_매번_하나씩_오른다() {
        seedDraft(1, 1);

        pageService.update(1L, page.getId(), new PageUpdateRequest("둘", "둘 본문", null, 1, null));
        pageService.update(1L, page.getId(), new PageUpdateRequest("셋", "셋 본문", null, 2, null));

        Map<String, Object> row = row();
        assertThat(((Number) row.get("base_page_version")).longValue()).isEqualTo(3L);
        assertThat(((Number) row.get("reset_epoch")).longValue()).isEqualTo(SEEDED_EPOCH + 2);
        assertThat(signal.sent).containsExactly(
                new RecordingCollaborationResetSignal.Sent(room(), SEEDED_EPOCH + 1),
                new RecordingCollaborationResetSignal.Sent(room(), SEEDED_EPOCH + 2));
    }

    @Test
    void 리비전_복원도_새_버전이므로_초안을_리셋한다() {
        seedDraft(1, 1);

        pageService.restore(1L, page.getId(), 1);

        assertReset(2, 2);
    }

    @Test
    void 작업_체크_토글도_초안을_리셋한다() {
        seedDraft(1, 1);

        taskService.setDone(1L, page.getId(), 1, true);

        assertReset(2, 2);
    }

    @Test
    void 재이관_갱신도_초안을_리셋한다() {
        seedDraft(1, 1);

        importWriter.update(page.getId(), new ImportedPageWriter.ImportedPage(
                space.getId(), null, null, "원본 제목", "원본 새 본문", 1L, "Jane", true, null,
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2021-01-01T00:00:00Z"),
                List.of(), null, null, List.of()), "재이관");

        assertReset(2, 2);
    }

    @Test
    void 이관_링크_정리_pass도_초안을_리셋한다() {
        seedDraft(1, 1);

        importWriter.rewriteBodyAsRevision(page.getId(), "링크 정리된 본문", 1L, "이관 링크 정리");

        assertReset(2, 2);
    }

    @Test
    void 첨부_URL_정리는_버전을_올리지_않아도_본문이_바뀌므로_초안을_리셋한다() {
        seedDraft(1, 1);

        importWriter.rewriteBody(page.getId(), "첨부 URL이 정리된 본문");

        assertReset(1, 2);
    }

    @Test
    void 리셋된_초안은_게시_경로로_옛_세대를_받아들이지_않는다() throws Exception {
        seedDraft(1, 1);
        pageService.update(1L, page.getId(), new PageUpdateRequest("새 제목", "새 본문", null, 1, null));

        // 옛 세션은 page v1·generation 1을 들고 있다 — 새 버전·새 세대와 맞지 않으므로 409
        mvc.perform(put("/api/wiki/pages/" + page.getId() + "/collaboration-draft")
                        .with(asUser(1L, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"옛 제목","content":"옛 공동 본문",
                                 "expectedPageVersion":1,"expectedGeneration":1}
                                """))
                .andExpect(status().isConflict());

        assertThat(pages.findById(page.getId()).orElseThrow().getContent()).isEqualTo("새 본문");
    }

    @Test
    void 게시_경로는_리셋하지_않고_generation만_전진하며_epoch는_그대로다() {
        seedDraft(1, 1);

        pageService.commitCollaborationDraft(1L, page.getId(),
                new CollaborationDraftCommitRequest("공유 제목", "공유 본문", 1, 1L));

        Map<String, Object> row = row();
        assertThat(((Number) row.get("base_page_version")).longValue()).isEqualTo(2L);
        assertThat(((Number) row.get("generation")).longValue()).isEqualTo(2L);
        assertThat(row.get("reset_pending")).isEqualTo(false);
        // C-1 회귀: 게시가 epoch를 올리면 collaboration-service store 가드가 남은 편집자를 쫓아낸다
        assertThat(((Number) row.get("reset_epoch")).longValue()).isEqualTo(SEEDED_EPOCH);
        assertThat((byte[]) row.get("state")).containsExactly(1, 2, 3, 4);
        assertThat(signal.sent).isEmpty();
    }
}
