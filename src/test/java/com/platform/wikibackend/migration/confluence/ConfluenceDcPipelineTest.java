package com.platform.wikibackend.migration.confluence;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.migration.MigrationJobService;
import com.platform.wikibackend.migration.confluence.dc.ConfluenceDcDiscoveryService;
import com.platform.wikibackend.migration.dto.MigrationDiscoverResponse;
import com.platform.wikibackend.migration.model.MigrationItem;
import com.platform.wikibackend.migration.model.MigrationItemStatus;
import com.platform.wikibackend.migration.model.MigrationJob;
import com.platform.wikibackend.migration.model.MigrationJobMode;
import com.platform.wikibackend.migration.model.MigrationJobStatus;
import com.platform.wikibackend.migration.model.MigrationPayloadKind;
import com.platform.wikibackend.migration.model.MigrationProvider;
import com.platform.wikibackend.migration.model.MigrationSource;
import com.platform.wikibackend.migration.model.MigrationStage;
import com.platform.wikibackend.migration.repository.MigrationIssueRepository;
import com.platform.wikibackend.migration.repository.MigrationItemRepository;
import com.platform.wikibackend.migration.repository.MigrationJobRepository;
import com.platform.wikibackend.migration.repository.MigrationObjectMappingRepository;
import com.platform.wikibackend.migration.repository.MigrationPayloadRepository;
import com.platform.wikibackend.migration.repository.MigrationSourceRepository;
import com.platform.wikibackend.migration.worker.MigrationWorker;
import com.platform.wikibackend.repository.PageLabelRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 발견 → 실행 → 재실행까지 파이프라인 전체를 가짜 DC 서버로 한 번 완주한다(성공지표 S1).
 *
 * 단계별 단위 테스트로는 잡히지 않는 것들이 여기서 잡힌다: 부모가 자식보다 먼저 처리되는가,
 * 재실행이 문서를 늘리지 않는가, 원본이 바뀌면 갱신되는가, 시각·라벨·작성자 이름이 남는가.
 */
@SpringBootTest(properties = {
        "platform.wiki.migration.dc.page-size=2",
})
@ActiveProfiles("test")
class ConfluenceDcPipelineTest {

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
    @Autowired PageLabelRepository labels;
    @Autowired SpaceRepository spaces;
    @Autowired FakePermissionClient permissions;

    private FakeConfluenceDcServer dc;
    private Long spaceId;

    @BeforeEach
    void setUp() throws Exception {
        dc = new FakeConfluenceDcServer();
        permissions.reset();
        issues.deleteAllInBatch();
        payloads.deleteAllInBatch();
        mappings.deleteAllInBatch();
        items.deleteAllInBatch();
        sources.deleteAllInBatch();
        jobs.deleteAllInBatch();
        revisions.deleteAllInBatch();
        labels.deleteAllInBatch();
        pages.deleteAllInBatch();
        spaces.deleteAllInBatch();
        spaceId = spaces.save(Space.of("mig" + (System.nanoTime() % 100000), "이관 대상", null, ADMIN)).getId();
        permissions.allow(ADMIN, spaceId, WikiAction.ADMIN);
    }

    @AfterEach
    void tearDown() {
        dc.stop();
    }

    @Test
    void 한_스페이스를_끝까지_옮기고_다시_돌려도_문서가_늘지_않는다() {
        MigrationJob job = importJob();

        MigrationDiscoverResponse discovered = discovery.discover(job.getId(), NOW);
        assertThat(discovered.discovered()).isEqualTo(3);
        assertThat(discovered.enqueued()).isEqualTo(3);
        assertThat(discovered.skipped()).isZero();
        // 조상 깊이 오름차순으로 담아야 RESOLVE에서 부모의 대상 id를 찾을 수 있다.
        assertThat(items.findFiltered(job.getId(), null, null,
                        org.springframework.data.domain.PageRequest.of(0, 50)).getContent())
                .extracting(MigrationItem::getExternalObjectId)
                .containsExactly("10001", "10002", "10003");

        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationJobStatus.COMPLETED);
        assertThat(pages.count()).isEqualTo(3);

        Page root = pageTitled("서비스 운영 가이드");
        Page child = pageTitled("장애 대응 절차");
        assertThat(child.getParentId()).isEqualTo(root.getId());
        // 시각은 원본 것이어야 한다 — "마지막 수정일"이 이관한 날짜면 사용자에게 거짓말이 된다.
        assertThat(root.getCreatedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(root.getUpdatedAt()).isEqualTo(Instant.parse("2026-08-17T00:00:00Z"));
        assertThat(labels.findByPageIdOrderByName(root.getId()))
                .extracting(l -> l.getName()).containsExactlyInAnyOrder("운영", "런북");
        // 원본 작성자 이름은 리비전 편집자 이름 스냅샷(V28)으로 남는다.
        assertThat(revisions.findByPageIdOrderByVersionDesc(root.getId()))
                .extracting(PageRevision::getEditedByName)
                .containsExactly("김운영");

        // 재실행: 원본이 그대로면 새 문서도, 새 리비전도 없다(S1 멱등).
        MigrationJob second = importJob();
        discovery.discover(second.getId(), NOW);
        migrations.start(ADMIN, second.getId(), NOW);
        worker.drain(second.getId(), 100, () -> NOW);

        assertThat(pages.count()).isEqualTo(3);
        assertThat(revisions.findByPageIdOrderByVersionDesc(root.getId())).hasSize(1);
        assertThat(jobs.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationJobStatus.COMPLETED);
    }

    @Test
    void 원본이_바뀌면_새_리비전으로_갱신된다() {
        MigrationJob first = importJob();
        discovery.discover(first.getId(), NOW);
        migrations.start(ADMIN, first.getId(), NOW);
        worker.drain(first.getId(), 100, () -> NOW);
        long pageId = pageTitled("장애 대응 절차").getId();

        dc.updatePage("10002", "장애 대응 절차 v2", "<p>새 절차입니다.</p>", 9);
        MigrationJob second = importJob();
        discovery.discover(second.getId(), NOW);
        migrations.start(ADMIN, second.getId(), NOW);
        worker.drain(second.getId(), 100, () -> NOW);

        Page updated = pages.findById(pageId).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("장애 대응 절차 v2");
        assertThat(updated.getContent()).contains("새 절차입니다.");
        assertThat(pages.count()).isEqualTo(3);
        assertThat(revisions.findByPageIdOrderByVersionDesc(pageId))
                .hasSize(2)
                .first()
                .satisfies(revision -> assertThat(revision.getChangeNote())
                        .isEqualTo("컨플루언스 재이관 v9"));
    }

    @Test
    void dry_run은_문서를_한_건도_만들지_않고_마크다운까지만_남긴다() {
        MigrationJob job = createJob(MigrationJobMode.DRY_RUN);
        discovery.discover(job.getId(), NOW);
        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);

        assertThat(pages.count()).isZero();
        assertThat(mappings.count()).isZero();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationJobStatus.COMPLETED);
        MigrationItem item = items.findByJobIdAndSourceKey(job.getId(),
                MigrationItem.sourceKeyFor("10001")).orElseThrow();
        assertThat(item.getStage()).isEqualTo(MigrationStage.DONE);
        assertThat(payloads.findByItemIdAndKind(item.getId(), MigrationPayloadKind.MARKDOWN))
                .isPresent();
    }

    @Test
    void 미지원_매크로와_첨부는_손실_보고서에_남는다() {
        MigrationJob job = createJob(MigrationJobMode.DRY_RUN);
        discovery.discover(job.getId(), NOW);
        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);

        List<String> codes = issues.findByJobIdOrderByIdAsc(job.getId()).stream()
                .map(issue -> issue.getCode())
                .toList();
        assertThat(codes).contains("CONFLUENCE_UNSUPPORTED_MACRO", "ATTACHMENT_NOT_COPIED");

        // 집계에는 대표 위치가 함께 실려야 화면에서 "무엇이 문제였는지"까지 보인다.
        assertThat(issues.summarize(job.getId()))
                .filteredOn(summary -> "ATTACHMENT_NOT_COPIED".equals(summary.code()))
                .singleElement()
                .satisfies(summary ->
                        assertThat(summary.sampleSourcePath()).isEqualTo("attachment:topology.png"));
    }

    @Test
    void 발견하지_않고_시작하면_400이다() {
        MigrationJob job = importJob();

        assertThatThrownBy(() -> migrations.start(ADMIN, job.getId(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MigrationJobService.MIGRATION_NOTHING_DISCOVERED);
    }

    @Test
    void 재발견은_새_항목만_담는다() {
        MigrationJob job = importJob();
        discovery.discover(job.getId(), NOW);

        MigrationDiscoverResponse again = discovery.discover(job.getId(), NOW);

        assertThat(again.discovered()).isEqualTo(3);
        assertThat(again.enqueued()).isZero();
        assertThat(again.skipped()).isEqualTo(3);
        assertThat(items.countByJobId(job.getId())).isEqualTo(3);
        assertThat(sources.findById(job.getId()).orElseThrow())
                .satisfies(source -> {
                    assertThat(source.getDiscoveredCount()).isEqualTo(3);
                    assertThat(source.getSourceSpaceName()).isEqualTo("Engineering");
                });
    }

    @Test
    void 원본에서_사라진_문서는_데드레터로_가고_잡은_FAILED다() {
        MigrationJob job = importJob();
        discovery.discover(job.getId(), NOW);
        dc.removePage("10003");
        migrations.start(ADMIN, job.getId(), NOW);
        worker.drain(job.getId(), 100, () -> NOW);

        MigrationItem missing = items.findByJobIdAndSourceKey(job.getId(),
                MigrationItem.sourceKeyFor("10003")).orElseThrow();
        assertThat(missing.getStatus()).isEqualTo(MigrationItemStatus.DEAD_LETTER);
        assertThat(missing.getLastErrorCode()).isEqualTo("DC_NOT_FOUND");
        // 나머지 두 건은 정상적으로 옮겨진다 — 한 건 때문에 전체를 되돌리지 않는다.
        assertThat(pages.count()).isEqualTo(2);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(MigrationJobStatus.FAILED);
    }

    private MigrationJob importJob() {
        return createJob(MigrationJobMode.IMPORT);
    }

    private MigrationJob createJob(MigrationJobMode mode) {
        MigrationJob job = jobs.save(MigrationJob.create(MigrationProvider.CONFLUENCE_DC,
                "127.0.0.1", spaceId, ADMIN, mode));
        sources.save(MigrationSource.of(job.getId(), dc.baseUrl(), "ENG", "test-token"));
        return job;
    }

    private Page pageTitled(String title) {
        return pages.findAll().stream()
                .filter(page -> title.equals(page.getTitle()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("문서를 찾을 수 없다: " + title));
    }
}
