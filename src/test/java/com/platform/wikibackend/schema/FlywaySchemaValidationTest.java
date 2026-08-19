package com.platform.wikibackend.schema;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.migration.model.MigrationIssue;
import com.platform.wikibackend.migration.model.MigrationIssueSeverity;
import com.platform.wikibackend.migration.model.MigrationItem;
import com.platform.wikibackend.migration.model.MigrationItemStatus;
import com.platform.wikibackend.migration.model.MigrationJob;
import com.platform.wikibackend.migration.model.MigrationJobMode;
import com.platform.wikibackend.migration.model.MigrationProvider;
import com.platform.wikibackend.migration.repository.MigrationIssueRepository;
import com.platform.wikibackend.migration.repository.MigrationItemRepository;
import com.platform.wikibackend.migration.repository.MigrationJobRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 마이그레이션 ↔ JPA 엔티티 정합을 실제 Postgres로 검증한다.
 *
 * 나머지 테스트는 H2 + `ddl-auto: create-drop`이라 **마이그레이션을 아예 타지 않는다** —
 * 엔티티에 필드를 추가하고 `V*.sql`을 빠뜨려도 전부 통과하고, 운영에서 `ddl-auto: validate`가
 * 부팅을 거부한다. 그 간극을 이 테스트가 메운다.
 *
 * 여기서 하는 일:
 * 1. 빈 Postgres에 Flyway로 V1→현재 버전을 적용하고
 * 2. `ddl-auto: validate`로 컨텍스트를 띄운다(불일치면 컨텍스트 로딩 실패 = 테스트 실패)
 * 3. 실제 INSERT/SELECT로 H2에는 없는 제약(FK·CHECK)까지 살아 있는지 본다.
 *
 * Docker가 필요하다. 없으면 이 테스트만 실패하므로, 로컬에서 Docker 없이 돌릴 땐
 * `--tests '*' -x` 대신 `./gradlew test --tests '!*FlywaySchemaValidationTest'`로 제외한다.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none",
})
@ActiveProfiles("test")
@Testcontainers
class FlywaySchemaValidationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired MigrationJobRepository migrationJobs;
    @Autowired MigrationItemRepository migrationItems;
    @Autowired MigrationIssueRepository migrationIssues;

    /**
     * 컨텍스트가 떴다는 것 자체가 "마이그레이션 결과 스키마 == 엔티티 매핑"의 증거다
     * (validate가 컬럼 누락·타입 불일치에서 부팅을 거부한다).
     */
    @Test
    void 마이그레이션_스키마로_엔티티_검증이_통과한다() {
        assertThat(postgres.isRunning()).isTrue();
    }

    @Test
    void V2가_추가한_type_status가_실제로_저장되고_읽힌다() {
        Space s = spaces.save(Space.of("dev", "개발", null, 1L));
        Page folder = pages.save(Page.of(s.getId(), null, "폴더", "", 1L, PageType.FOLDER, PageStatus.PUBLISHED));
        Page draft = pages.save(Page.of(s.getId(), null, "초안", "", 1L, PageType.PAGE, PageStatus.DRAFT));

        assertThat(pages.findById(folder.getId()).orElseThrow().getType()).isEqualTo(PageType.FOLDER);
        assertThat(pages.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(PageStatus.DRAFT);
    }

    /**
     * H2 테스트 스키마엔 FK가 아예 없다(Wave B에서 세 번 물린 지점) — 운영 Postgres에는
     * `page.parent_id → page.id ON DELETE CASCADE`가 실제로 걸려 있는지 확인한다.
     */
    @Test
    void 부모_페이지를_지우면_DB_cascade로_자식도_사라진다() {
        Space s = spaces.save(Space.of("ops", "운영", null, 1L));
        Page parent = pages.save(Page.of(s.getId(), null, "부모", "", 1L));
        Page child = pages.save(Page.of(s.getId(), parent.getId(), "자식", "", 1L));

        pages.deleteById(parent.getId());
        pages.flush();

        assertThat(pages.findById(child.getId())).isEmpty();
    }

    @Test
    void V6_migration_checkpoint가_실제_Postgres에_저장된다() {
        Space space = spaces.save(Space.of("migration", "마이그레이션", null, 1L));
        MigrationJob job = migrationJobs.save(MigrationJob.create(
                MigrationProvider.NOTION, "workspace-acme", space.getId(), 1L, MigrationJobMode.DRY_RUN));
        MigrationItem item = migrationItems.save(MigrationItem.pending(
                job.getId(), "page-42", "v1", "d".repeat(64),
                "imports/notion/job-1/page-42.json"));
        migrationIssues.save(MigrationIssue.of(
                job.getId(), item.getId(), MigrationIssueSeverity.WARNING,
                "UNSUPPORTED_BLOCK", "/blocks/4"));

        assertThat(migrationItems.findByJobIdAndSourceKey(job.getId(), item.getSourceKey()))
                .get()
                .extracting(MigrationItem::getId)
                .isEqualTo(item.getId());
        assertThat(migrationIssues.findByJobIdOrderByIdAsc(job.getId())).hasSize(1);
    }

    /**
     * V7의 lease CHECK는 H2 스키마(create-drop)에는 없다. RUNNING과 소유자/만료가 항상 함께
     * 움직이는지는 실제 Postgres에서만 확인된다.
     */
    @Test
    void V7_worker_lease는_RUNNING_상태와_함께만_존재한다() {
        Space space = spaces.save(Space.of("lease", "점유", null, 1L));
        MigrationJob job = migrationJobs.save(MigrationJob.create(
                MigrationProvider.NOTION, "workspace-acme", space.getId(), 1L, MigrationJobMode.IMPORT));
        MigrationItem item = migrationItems.saveAndFlush(MigrationItem.pending(
                job.getId(), "page-77", "v1", "e".repeat(64), "imports/notion/job-2/page-77.json"));
        Instant claimedAt = Instant.parse("2026-08-18T09:00:00Z");

        assertThat(migrationItems.claim(item.getId(), "worker-a", "token-1",
                claimedAt.plusSeconds(300), claimedAt, MigrationItemStatus.RUNNING,
                MigrationItemStatus.PENDING, MigrationItemStatus.RETRY_WAIT)).isEqualTo(1);

        assertThat(migrationItems.findById(item.getId()).orElseThrow())
                .satisfies(running -> {
                    assertThat(running.getStatus()).isEqualTo(MigrationItemStatus.RUNNING);
                    assertThat(running.getClaimedBy()).isEqualTo("worker-a");
                    assertThat(running.getClaimToken()).isEqualTo("token-1");
                    assertThat(running.getLeaseExpiresAt()).isEqualTo(claimedAt.plusSeconds(300));
                });

        MigrationItem running = migrationItems.findById(item.getId()).orElseThrow();
        running.scheduleRetry("NOTION_TIMEOUT", claimedAt.plusSeconds(60));
        migrationItems.saveAndFlush(running);

        assertThat(migrationItems.findById(item.getId()).orElseThrow())
                .satisfies(waiting -> {
                    assertThat(waiting.getStatus()).isEqualTo(MigrationItemStatus.RETRY_WAIT);
                    assertThat(waiting.getClaimedBy()).isNull();
                    assertThat(waiting.getClaimToken()).isNull();
                    assertThat(waiting.getLeaseExpiresAt()).isNull();
                });
    }
}
