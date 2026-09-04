package com.platform.wikibackend.migration.confluence;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageComment;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.migration.MigrationJobService;
import com.platform.wikibackend.migration.confluence.dc.ConfluenceDcDiscoveryService;
import com.platform.wikibackend.migration.dto.MigrationDiscoverResponse;
import com.platform.wikibackend.migration.model.MigrationJob;
import com.platform.wikibackend.migration.model.MigrationJobMode;
import com.platform.wikibackend.migration.model.MigrationJobStatus;
import com.platform.wikibackend.migration.model.MigrationProvider;
import com.platform.wikibackend.migration.model.MigrationSource;
import com.platform.wikibackend.migration.repository.MigrationIssueRepository;
import com.platform.wikibackend.migration.repository.MigrationItemRepository;
import com.platform.wikibackend.migration.repository.MigrationJobRepository;
import com.platform.wikibackend.migration.repository.MigrationObjectMappingRepository;
import com.platform.wikibackend.migration.repository.MigrationPayloadRepository;
import com.platform.wikibackend.migration.repository.MigrationSourceRepository;
import com.platform.wikibackend.migration.worker.MigrationWorker;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageLabelRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3에서 는 것들을 파이프라인으로 확인한다 — 블로그 글, 댓글, 버전 이력, 원본 작성자 표시.
 *
 * M1·M2 파이프라인 테스트와 클래스를 나눈 이유는 프로퍼티다. 이력 이관은 버전마다 원본 왕복이
 * 한 번씩 늘어, 그 값을 켠 채로 기존 시나리오를 돌리면 없는 버전을 찾는 404가 테스트마다 수십 번
 * 쌓인다. 여기서만 켜고 거기서는 끈다.
 */
@SpringBootTest(properties = {
        "platform.wiki.migration.dc.history-versions=3",
        // 가짜 지난 버전은 수십 바이트다. 상한을 2KB로 낮춰 "버전 하나가 너무 크다" 경로를
        // 실제 2MB 본문 없이 태운다.
        "platform.wiki.migration.dc.max-history-version-bytes=2048",
})
@ActiveProfiles("test")
class ConfluenceDcM3PipelineTest {

    private static final long ADMIN = 11L;
    private static final Instant NOW = Instant.parse("2026-09-05T09:00:00Z");

    @Autowired MigrationWorker worker;
    @Autowired MigrationJobService migrations;
    @Autowired ConfluenceDcDiscoveryService discovery;
    @Autowired MigrationJobRepository jobs;
    @Autowired MigrationItemRepository items;
    @Autowired MigrationIssueRepository issues;
    @Autowired MigrationSourceRepository sources;
    @Autowired MigrationPayloadRepository payloads;
    @Autowired MigrationObjectMappingRepository mappings;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageCommentRepository comments;
    @Autowired PageLabelRepository labels;
    @Autowired SpaceRepository spaces;
    @Autowired AttachmentRepository attachments;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired FakePermissionClient permissions;

    private FakeConfluenceDcServer dc;
    private Long spaceId;

    @BeforeEach
    void setUp() throws Exception {
        dc = new FakeConfluenceDcServer();
        permissions.reset();
        attachments.deleteAllInBatch();
        restrictions.deleteAllInBatch();
        issues.deleteAllInBatch();
        payloads.deleteAllInBatch();
        mappings.deleteAllInBatch();
        items.deleteAllInBatch();
        sources.deleteAllInBatch();
        jobs.deleteAllInBatch();
        comments.deleteAllInBatch();
        revisions.deleteAllInBatch();
        labels.deleteAllInBatch();
        pages.deleteAllInBatch();
        spaces.deleteAllInBatch();
        spaceId = spaces.save(Space.of("m3" + (System.nanoTime() % 100000), "이관 대상", null, ADMIN)).getId();
        permissions.allow(ADMIN, spaceId, WikiAction.ADMIN);

        // 원본을 문서 하나 + 블로그 하나로 줄인다 — M3가 더한 것만 보이게.
        dc.removePage("10002");
        dc.removePage("10003");
        dc.putPage("10001", "서비스 운영 가이드", null, "<p><strong>운영 변경</strong> 전 확인하세요.</p>",
                4, List.of(), List.of(), FakeConfluenceDcServer.FakeRestrictions.none());
    }

    @AfterEach
    void tearDown() {
        dc.stop();
    }

    // -- M3 5.1 블로그 글 ---------------------------------------------

    @Test
    void 블로그_글은_트리_밖에_BLOG_타입으로_만들어진다() {
        dc.putBlogPost("20001", "9월 릴리스 노트", "<p>이번 달 변경 사항입니다.</p>", 1);

        MigrationDiscoverResponse discovered = runImport();

        assertThat(discovered.discovered()).isEqualTo(2);
        Page blog = pageTitled("9월 릴리스 노트");
        assertThat(blog.getType()).isEqualTo(PageType.BLOG);
        assertThat(blog.getParentId()).isNull();
        // 블로그 목록에 뜬다 — 트리 쿼리에서는 빠지고 이 쿼리로만 읽힌다(W24).
        assertThat(pages.findBlogPosts(spaceId)).extracting(Page::getTitle)
                .containsExactly("9월 릴리스 노트");
        assertThat(pages.findChildren(spaceId, null)).extracting(item -> item.title())
                .containsExactly("서비스 운영 가이드");
        // 타입이 어긋나면 VERIFY가 잡는다 — 여기서는 잡을 것이 없어야 한다.
        assertThat(issueCodes()).doesNotContain("VERIFY_TYPE_MISMATCH");
    }

    // -- M3 5.2 댓글 --------------------------------------------------

    @Test
    void 댓글은_답글까지_옮겨지고_인라인은_인용과_함께_페이지_댓글로_내려온다() {
        seedComments();

        runImport();

        long pageId = pageTitled("서비스 운영 가이드").getId();
        List<PageComment> written = comments.findByPageIdOrderByCreatedAtAscIdAsc(pageId);
        assertThat(written).hasSize(3);

        PageComment top = written.get(0);
        assertThat(top.getBody()).isEqualTo("확인했습니다.");
        // 작성자 id는 잡 요청자, 이름은 원본 것이다(P2) — 계정을 새로 만들지 않는다.
        assertThat(top.getAuthorId()).isEqualTo(ADMIN);
        assertThat(top.getAuthorName()).isEqualTo("박댓글");
        assertThat(top.getCreatedAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(top.getParentId()).isNull();

        PageComment reply = written.get(1);
        assertThat(reply.getParentId()).isEqualTo(top.getId());
        assertThat(reply.getAuthorName()).isEqualTo("김운영");

        // 인라인 댓글은 페이지 댓글로 내려오되 무엇에 달린 말인지는 인용으로 남는다.
        PageComment demoted = written.get(2);
        assertThat(demoted.isInline()).isFalse();
        assertThat(demoted.getBody()).startsWith("> 원문: \"운영 변경\"");
        assertThat(demoted.getBody()).contains("여기 오타입니다.");
        assertThat(issueCodes()).contains("INLINE_COMMENT_DEMOTED");
    }

    @Test
    void 답글의_답글은_최상위_답글로_펴진다() {
        dc.putComments("10001", List.of(
                FakeConfluenceDcServer.FakeComment.footer("c1", "<p>첫 댓글</p>", "박댓글",
                        "2026-02-01T00:00:00Z"),
                FakeConfluenceDcServer.FakeComment.reply("c2", "c1", "<p>답글</p>", "김운영",
                        "2026-02-02T00:00:00Z"),
                FakeConfluenceDcServer.FakeComment.reply("c3", "c2", "<p>답글의 답글</p>", "이지원",
                        "2026-02-03T00:00:00Z")));

        runImport();

        long pageId = pageTitled("서비스 운영 가이드").getId();
        List<PageComment> written = comments.findByPageIdOrderByCreatedAtAscIdAsc(pageId);
        long topId = written.get(0).getId();
        // 우리 댓글의 중첩은 1단이다 — 손자는 부모가 아니라 할아버지에 붙는다.
        assertThat(written).extracting(PageComment::getParentId)
                .containsExactly(null, topId, topId);
        assertThat(issueCodes()).contains("COMMENT_REPLY_FLATTENED");
    }

    // -- M3 5.3 버전 이력 ---------------------------------------------

    @Test
    void 지난_버전은_오래된_것부터_리비전으로_쌓이고_현재본이_마지막이다() {
        seedHistory();

        runImport();

        Page page = pageTitled("서비스 운영 가이드");
        // 이력 3건 + 현재본 = 4. 페이지 버전 번호도 리비전 수와 같아야 이력이 끊기지 않는다.
        assertThat(page.getVersion()).isEqualTo(4);
        List<PageRevision> ordered =
                revisions.findByPageIdOrderByVersionDesc(page.getId()).reversed();
        assertThat(ordered).hasSize(4);
        assertThat(ordered).extracting(PageRevision::getEditedByName)
                .containsExactly("김초안", "박수정", "이검토", "김운영");
        assertThat(ordered).extracting(PageRevision::getChangeNote)
                .containsExactly("초안 작성", "절차 보강", "검토 반영", null);
        assertThat(ordered.get(0).getContent()).contains("첫 초안");
        assertThat(ordered.get(0).getCreatedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        // 마지막 리비전은 현재 본문이다.
        assertThat(ordered.get(3).getContent()).isEqualTo(page.getContent());
    }

    @Test
    void 상한을_넘는_지난_버전은_건너뛰고_보고서에_남는다() {
        // 두 번째 버전만 거대하게 만든다 — 상한(테스트에서 낮춘 2KB)을 넘겨 그 버전만 빠진다.
        dc.putHistory("10001", List.of(
                new FakeConfluenceDcServer.FakeVersion(1, "서비스 운영 가이드", "<p>첫 초안</p>",
                        "2026-01-02T03:04:05Z", "김초안", "초안 작성"),
                new FakeConfluenceDcServer.FakeVersion(2, "서비스 운영 가이드",
                        "<p>" + "가".repeat(3000) + "</p>", "2026-01-10T00:00:00Z", "박수정", "절차 보강"),
                new FakeConfluenceDcServer.FakeVersion(3, "서비스 운영 가이드", "<p>검토 반영본</p>",
                        "2026-02-01T00:00:00Z", "이검토", "검토 반영")));

        runImport();

        Page page = pageTitled("서비스 운영 가이드");
        assertThat(revisions.findByPageIdOrderByVersionDesc(page.getId())).hasSize(3);
        assertThat(issueCodes()).contains("HISTORY_VERSION_SKIPPED");
    }

    // -- M3 5.4 원본 작성자 표시 --------------------------------------

    @Test
    void 대조하지_못한_원본_작성자는_이름과_원본_주소로_남는다() {
        runImport();

        Page page = pageTitled("서비스 운영 가이드");
        assertThat(page.getImportedAuthorName()).isEqualTo("김운영");
        assertThat(page.getImportedSourceUrl())
                .isEqualTo(dc.baseUrl() + "/pages/viewpage.action?pageId=10001");
        // 우리 쪽 책임자는 여전히 이관 담당자다 — created_by를 원본 이름으로 덮지 않는다.
        assertThat(page.getCreatedBy()).isEqualTo(ADMIN);
        assertThat(issueCodes()).contains("AUTHOR_UNMAPPED");
    }

    // -- M3 재실행 멱등 -----------------------------------------------

    @Test
    void 다시_이관해도_댓글과_이력이_두_벌이_되지_않는다() {
        seedComments();
        seedHistory();
        dc.putBlogPost("20001", "9월 릴리스 노트", "<p>이번 달 변경 사항입니다.</p>", 1);
        runImport();

        long pageId = pageTitled("서비스 운영 가이드").getId();
        assertThat(comments.findByPageIdOrderByCreatedAtAscIdAsc(pageId)).hasSize(3);
        assertThat(revisions.findByPageIdOrderByVersionDesc(pageId)).hasSize(4);

        runImport();

        assertThat(pages.count()).isEqualTo(2);
        assertThat(comments.findByPageIdOrderByCreatedAtAscIdAsc(pageId)).hasSize(3);
        assertThat(revisions.findByPageIdOrderByVersionDesc(pageId)).hasSize(4);
    }

    private void seedComments() {
        dc.putComments("10001", List.of(
                FakeConfluenceDcServer.FakeComment.footer("c1", "<p>확인했습니다.</p>", "박댓글",
                        "2026-02-01T00:00:00Z"),
                FakeConfluenceDcServer.FakeComment.reply("c2", "c1", "<p>감사합니다.</p>", "김운영",
                        "2026-02-02T00:00:00Z"),
                FakeConfluenceDcServer.FakeComment.inline("c3", "<p>여기 오타입니다.</p>", "이지원",
                        "2026-02-03T00:00:00Z", "운영 변경")));
    }

    private void seedHistory() {
        dc.putHistory("10001", List.of(
                new FakeConfluenceDcServer.FakeVersion(1, "서비스 운영 가이드", "<p>첫 초안</p>",
                        "2026-01-02T03:04:05Z", "김초안", "초안 작성"),
                new FakeConfluenceDcServer.FakeVersion(2, "서비스 운영 가이드", "<p>절차를 보강했습니다.</p>",
                        "2026-01-10T00:00:00Z", "박수정", "절차 보강"),
                new FakeConfluenceDcServer.FakeVersion(3, "서비스 운영 가이드", "<p>검토 반영본</p>",
                        "2026-02-01T00:00:00Z", "이검토", "검토 반영")));
    }

    private MigrationDiscoverResponse runImport() {
        MigrationJob job = jobs.save(MigrationJob.create(MigrationProvider.CONFLUENCE_DC,
                "127.0.0.1", spaceId, ADMIN, MigrationJobMode.IMPORT));
        sources.save(MigrationSource.of(job.getId(), dc.baseUrl(), "ENG", "test-token"));
        MigrationDiscoverResponse discovered = discovery.discover(job.getId(), NOW);
        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationJobStatus.COMPLETED);
        return discovered;
    }

    private List<String> issueCodes() {
        return issues.findAll().stream().map(issue -> issue.getCode()).toList();
    }

    private Page pageTitled(String title) {
        return pages.findAll().stream()
                .filter(page -> title.equals(page.getTitle()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("문서를 찾을 수 없다: " + title));
    }
}
