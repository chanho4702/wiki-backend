package com.platform.wikibackend.migration;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.migration.model.MigrationIssue;
import com.platform.wikibackend.migration.model.MigrationIssueSeverity;
import com.platform.wikibackend.migration.model.MigrationItem;
import com.platform.wikibackend.migration.model.MigrationItemStatus;
import com.platform.wikibackend.migration.model.MigrationJob;
import com.platform.wikibackend.migration.model.MigrationJobMode;
import com.platform.wikibackend.migration.model.MigrationJobStatus;
import com.platform.wikibackend.migration.model.MigrationObjectMapping;
import com.platform.wikibackend.migration.model.MigrationProvider;
import com.platform.wikibackend.migration.model.MigrationStage;
import com.platform.wikibackend.migration.repository.MigrationIssueRepository;
import com.platform.wikibackend.migration.repository.MigrationItemRepository;
import com.platform.wikibackend.migration.repository.MigrationJobRepository;
import com.platform.wikibackend.migration.repository.MigrationObjectMappingRepository;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class MigrationCheckpointRepositoryTest {

    private static final String CHECKSUM = "a".repeat(64);

    @Autowired MigrationIssueRepository issues;
    @Autowired MigrationObjectMappingRepository mappings;
    @Autowired MigrationItemRepository items;
    @Autowired MigrationJobRepository jobs;
    @Autowired AttachmentRepository attachments;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageRepository pages;
    @Autowired SpaceRepository spaces;

    @BeforeEach
    void clean() {
        issues.deleteAllInBatch();
        mappings.deleteAllInBatch();
        items.deleteAllInBatch();
        jobs.deleteAllInBatch();
        attachments.deleteAllInBatch();
        revisions.deleteAllInBatch();
        pages.deleteAllInBatch();
        spaces.deleteAllInBatch();
    }

    @Test
    void job과_item은_단계_retry_checkpoint를_보존한다() {
        Space space = spaces.save(Space.of("mig", "Migration", null, 1L));
        MigrationJob job = jobs.save(MigrationJob.create(
                MigrationProvider.NOTION, "workspace-acme", space.getId(), 7L, MigrationJobMode.DRY_RUN));
        MigrationItem item = items.save(MigrationItem.pending(
                job.getId(), "page-42", "2026-08-17T00:00:00Z", CHECKSUM,
                "imports/notion/job-1/source/page-42.json"));
        Instant startedAt = Instant.parse("2026-08-17T00:01:00Z");
        Instant retryAt = startedAt.plusSeconds(60);

        job.start(startedAt);
        item.begin(startedAt);
        item.scheduleRetry("NOTION_RATE_LIMITED", retryAt);
        jobs.saveAndFlush(job);
        items.saveAndFlush(item);

        MigrationItem waiting = items.findById(item.getId()).orElseThrow();
        assertThat(waiting.getStage()).isEqualTo(MigrationStage.EXTRACT);
        assertThat(waiting.getStatus()).isEqualTo(MigrationItemStatus.RETRY_WAIT);
        assertThat(waiting.getRetryCount()).isEqualTo(1);
        assertThat(waiting.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(jobs.findByStatusOrderByCreatedAtAscIdAsc(MigrationJobStatus.RUNNING)).containsExactly(job);

        assertThatThrownBy(() -> waiting.begin(retryAt.minusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not due");

        waiting.begin(retryAt);
        waiting.completeStage(MigrationStage.NORMALIZE);
        items.saveAndFlush(waiting);

        assertThat(items.findById(item.getId()).orElseThrow())
                .satisfies(saved -> {
                    assertThat(saved.getStage()).isEqualTo(MigrationStage.NORMALIZE);
                    assertThat(saved.getStatus()).isEqualTo(MigrationItemStatus.PENDING);
                    assertThat(saved.getNextAttemptAt()).isNull();
                });
    }

    @Test
    void 같은_job의_external_object는_hash_key로_중복을_막는다() {
        Space space = spaces.save(Space.of("dedupe", "Dedupe", null, 1L));
        MigrationJob job = jobs.save(MigrationJob.create(
                MigrationProvider.CONFLUENCE_DC, "dc-cluster", space.getId(), 7L, MigrationJobMode.IMPORT));
        items.saveAndFlush(MigrationItem.pending(
                job.getId(), "content-10001", "27", CHECKSUM, "imports/confluence/job-1/content-10001.xhtml"));

        assertThatThrownBy(() -> items.saveAndFlush(MigrationItem.pending(
                job.getId(), "content-10001", "27", CHECKSUM, "imports/confluence/job-1/duplicate.xhtml")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void issue와_object_mapping에는_본문_대신_code_path_checksum만_남긴다() {
        Space space = spaces.save(Space.of("audit", "Audit", null, 1L));
        Page page = pages.save(Page.of(space.getId(), null, "Imported", "", 7L));
        MigrationJob job = jobs.save(MigrationJob.create(
                MigrationProvider.NOTION, "workspace-acme", space.getId(), 7L, MigrationJobMode.IMPORT));
        MigrationItem item = items.save(MigrationItem.pending(
                job.getId(), "page-42", "v3", CHECKSUM, "imports/notion/job-2/page-42.json"));
        MigrationIssue issue = issues.save(MigrationIssue.of(
                job.getId(), item.getId(), MigrationIssueSeverity.WARNING,
                "UNSUPPORTED_BLOCK", "/blocks/4"));
        MigrationObjectMapping mapping = mappings.save(MigrationObjectMapping.create(
                MigrationProvider.NOTION, "workspace-acme", "page-42", "v3", CHECKSUM,
                page.getId(), job.getId()));

        assertThat(issues.findByJobIdOrderByIdAsc(job.getId())).containsExactly(issue);
        assertThat(issues.findByItemIdAndIssueKey(item.getId(), MigrationIssue.issueKeyFor(
                "UNSUPPORTED_BLOCK", "/blocks/4"))).contains(issue);
        assertThat(issue.getIssueKey()).matches("[a-f0-9]{64}");
        assertThat(mapping.getSourceKey()).matches("[a-f0-9]{64}");
        assertThat(mapping.getSourceKey()).doesNotContain("page-42", "workspace-acme");
        assertThat(mappings.findBySourceKey(MigrationObjectMapping.sourceKeyFor(
                MigrationProvider.NOTION, "workspace-acme", "page-42"))).contains(mapping);
        assertThat(MigrationObjectMapping.sourceKeyFor(MigrationProvider.NOTION, "a\nb", "c"))
                .isNotEqualTo(MigrationObjectMapping.sourceKeyFor(MigrationProvider.NOTION, "a", "b\nc"));
    }
}
