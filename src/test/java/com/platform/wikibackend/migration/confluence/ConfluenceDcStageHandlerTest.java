package com.platform.wikibackend.migration.confluence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.migration.MigrationPayloadStore;
import com.platform.wikibackend.migration.confluence.handler.ConfluenceDcExtractHandler;
import com.platform.wikibackend.migration.confluence.handler.ConfluenceDcIssues;
import com.platform.wikibackend.migration.confluence.handler.ConfluenceDcMediaCopyHandler;
import com.platform.wikibackend.migration.confluence.handler.ConfluenceDcNormalizeHandler;
import com.platform.wikibackend.migration.confluence.handler.ConfluenceDcResolveHandler;
import com.platform.wikibackend.migration.confluence.handler.ConfluenceDcVerifyHandler;
import com.platform.wikibackend.migration.model.MigrationItem;
import com.platform.wikibackend.migration.model.MigrationJob;
import com.platform.wikibackend.migration.model.MigrationJobMode;
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
import com.platform.wikibackend.migration.worker.MigrationStageException;
import com.platform.wikibackend.migration.worker.MigrationStageIssue;
import com.platform.wikibackend.migration.worker.MigrationStageOutcome;
import com.platform.wikibackend.migration.worker.MigrationStageWork;
import com.platform.wikibackend.repository.PageLabelRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 단계 handler 5종을 하나씩 직접 불러 경계 동작을 고정한다.
 *
 * 파이프라인 테스트가 "잘 되는 경우"를 통째로 보는 반면, 여기서는 어긋난 입력에 handler가 무엇을
 * 하는지를 본다 — 조용히 넘어가면 안 되는 것들이라 대부분 경고 코드 하나를 확인하는 형태다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfluenceDcStageHandlerTest {

    private static final long ADMIN = 21L;
    private static final String CHECKSUM = "a".repeat(64);

    @Autowired ConfluenceDcExtractHandler extract;
    @Autowired ConfluenceDcNormalizeHandler normalize;
    @Autowired ConfluenceDcMediaCopyHandler mediaCopy;
    @Autowired ConfluenceDcResolveHandler resolve;
    @Autowired ConfluenceDcVerifyHandler verify;
    @Autowired MigrationPayloadStore payloads;
    @Autowired MigrationJobRepository jobs;
    @Autowired MigrationItemRepository items;
    @Autowired MigrationIssueRepository issues;
    @Autowired MigrationSourceRepository sources;
    @Autowired MigrationPayloadRepository payloadRows;
    @Autowired MigrationObjectMappingRepository mappings;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageLabelRepository labels;
    @Autowired SpaceRepository spaces;
    @Autowired ObjectMapper json;

    private FakeConfluenceDcServer dc;
    private Long spaceId;
    private MigrationJob job;

    @BeforeEach
    void setUp() throws Exception {
        dc = new FakeConfluenceDcServer();
        issues.deleteAllInBatch();
        payloadRows.deleteAllInBatch();
        mappings.deleteAllInBatch();
        items.deleteAllInBatch();
        sources.deleteAllInBatch();
        jobs.deleteAllInBatch();
        revisions.deleteAllInBatch();
        labels.deleteAllInBatch();
        pages.deleteAllInBatch();
        spaces.deleteAllInBatch();
        spaceId = spaces.save(Space.of("h" + (System.nanoTime() % 100000), "핸들러", null, ADMIN)).getId();
        job = jobs.save(MigrationJob.create(MigrationProvider.CONFLUENCE_DC, "127.0.0.1",
                spaceId, ADMIN, MigrationJobMode.IMPORT));
        sources.save(MigrationSource.of(job.getId(), dc.baseUrl(), "ENG", "test-token"));
    }

    @AfterEach
    void tearDown() {
        dc.stop();
    }

    @Test
    void EXTRACT는_정규화기가_읽는_필드만_담은_스냅샷을_남긴다() throws Exception {
        MigrationItem item = enqueue("10001", "27");

        MigrationStageOutcome outcome = extract.handle(work(item, MigrationStage.EXTRACT, "27"));

        assertThat(outcome.issues()).isEmpty();
        var snapshot = json.readTree(payloads.require(item.getId(), MigrationPayloadKind.SNAPSHOT).body());
        assertThat(snapshot.fieldNames()).toIterable().containsExactlyInAnyOrder("snapshotVersion", "content");
        assertThat(snapshot.path("content").path("title").asText()).isEqualTo("서비스 운영 가이드");
        // 원본 응답의 _links는 담지 않는다 — 스냅샷은 DC 버전 차이에 노출되지 않는 우리 계약이다.
        assertThat(snapshot.path("content").has("_links")).isFalse();
    }

    @Test
    void 발견_이후_원본이_수정됐으면_경고를_남기고_최신본을_가져온다() {
        MigrationItem item = enqueue("10001", "27");
        dc.updatePage("10001", "서비스 운영 가이드", "<p>바뀐 본문</p>", 28);

        MigrationStageOutcome outcome = extract.handle(work(item, MigrationStage.EXTRACT, "27"));

        assertThat(codes(outcome)).containsExactly(ConfluenceDcIssues.SOURCE_VERSION_DRIFT);
    }

    @Test
    void NORMALIZE는_깨진_스냅샷을_재시도하지_않고_실패시킨다() {
        MigrationItem item = enqueue("10001", "1");
        payloads.write(item.getId(), MigrationPayloadKind.SNAPSHOT, "{\"snapshotVersion\":1}");

        assertThatThrownBy(() -> normalize.handle(work(item, MigrationStage.NORMALIZE, "1")))
                .isInstanceOfSatisfying(MigrationStageException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ConfluenceDcIssues.SNAPSHOT_INVALID);
                    // 같은 XHTML을 다시 읽어도 결과가 같다 — 재시도는 데드레터를 늦출 뿐이다.
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void NORMALIZE는_정규화_손실을_그대로_보고한다() {
        MigrationItem item = enqueue("10001", "27");
        extract.handle(work(item, MigrationStage.EXTRACT, "27"));

        MigrationStageOutcome outcome = normalize.handle(work(item, MigrationStage.NORMALIZE, "27"));

        assertThat(codes(outcome)).contains("CONFLUENCE_UNSUPPORTED_MACRO");
        assertThat(payloads.read(item.getId(), MigrationPayloadKind.IR)).isPresent();
    }

    @Test
    void MEDIA_COPY는_옮기지_못한_첨부를_경고로_남긴다() {
        MigrationItem item = enqueue("10001", "27");
        extract.handle(work(item, MigrationStage.EXTRACT, "27"));
        normalize.handle(work(item, MigrationStage.NORMALIZE, "27"));

        MigrationStageOutcome outcome = mediaCopy.handle(work(item, MigrationStage.MEDIA_COPY, "27"));

        assertThat(outcome.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo(ConfluenceDcIssues.ATTACHMENT_NOT_COPIED);
                    assertThat(issue.sourcePath()).isEqualTo("attachment:topology.png");
                });
    }

    @Test
    void RESOLVE는_조상을_못_찾으면_루트에_두고_경고한다() {
        // 부모(10001)를 옮기지 않은 채 자식만 처리한다 — 조상이 데드레터로 빠진 상황이다.
        MigrationItem item = enqueue("10002", "3");
        extract.handle(work(item, MigrationStage.EXTRACT, "3"));
        normalize.handle(work(item, MigrationStage.NORMALIZE, "3"));

        MigrationStageOutcome outcome = resolve.handle(work(item, MigrationStage.RESOLVE, "3"));

        assertThat(codes(outcome)).contains(ConfluenceDcIssues.PARENT_NOT_FOUND,
                ConfluenceDcIssues.AUTHOR_UNMAPPED);
        Page created = pages.findById(outcome.targetPageId()).orElseThrow();
        assertThat(created.getParentId()).isNull();
    }

    /**
     * 잘라내기는 EXTRACT에서 일어난다 — IR 계약의 title 상한도 255라, 스냅샷에서 미리 자르지 않으면
     * NORMALIZE가 검증 실패로 항목을 데드레터시켜 문서가 아예 안 넘어온다.
     */
    @Test
    void 아주_긴_제목은_스냅샷_단계에서_잘리고_문서까지_옮겨진다() {
        String longTitle = "가".repeat(300);
        dc.updatePage("10002", longTitle, "<p>본문</p>", 4);
        MigrationItem item = enqueue("10002", "4");

        MigrationStageOutcome extracted = extract.handle(work(item, MigrationStage.EXTRACT, "4"));
        assertThat(codes(extracted)).contains(ImportedPageWriter.TITLE_TRUNCATED);

        normalize.handle(work(item, MigrationStage.NORMALIZE, "4"));
        MigrationStageOutcome outcome = resolve.handle(work(item, MigrationStage.RESOLVE, "4"));

        assertThat(pages.findById(outcome.targetPageId()).orElseThrow().getTitle())
                .hasSize(ImportedPageWriter.MAX_TITLE_LENGTH);
    }

    @Test
    void VERIFY는_제목이_어긋나면_ERROR로_보고한다() {
        MigrationItem item = enqueue("10002", "3");
        extract.handle(work(item, MigrationStage.EXTRACT, "3"));
        normalize.handle(work(item, MigrationStage.NORMALIZE, "3"));
        MigrationStageOutcome resolved = resolve.handle(work(item, MigrationStage.RESOLVE, "3"));
        Page page = pages.findById(resolved.targetPageId()).orElseThrow();
        page.edit("사람이 바꾼 제목", page.getContent(), ADMIN);
        pages.saveAndFlush(page);

        MigrationStageOutcome outcome = verify.handle(
                workWithPage(item, MigrationStage.VERIFY, "3", page.getId()));

        assertThat(codes(outcome)).contains(ConfluenceDcIssues.VERIFY_TITLE_MISMATCH);
        // 대조 실패는 예외가 아니다 — 재시도가 같은 문서를 또 쓰면 안 된다.
        assertThat(outcome.targetPageId()).isEqualTo(page.getId());
    }

    @Test
    void VERIFY는_대상_문서가_사라졌으면_ERROR로_보고한다() {
        MigrationItem item = enqueue("10002", "3");

        MigrationStageOutcome outcome = workVerifyWithMissingPage(item);

        assertThat(codes(outcome)).containsExactly(ConfluenceDcIssues.VERIFY_PAGE_MISSING);
    }

    private MigrationStageOutcome workVerifyWithMissingPage(MigrationItem item) {
        return verify.handle(workWithPage(item, MigrationStage.VERIFY, "3", 999_999L));
    }

    private List<String> codes(MigrationStageOutcome outcome) {
        return outcome.issues().stream().map(MigrationStageIssue::code).toList();
    }

    private MigrationItem enqueue(String externalId, String version) {
        return items.saveAndFlush(MigrationItem.pending(job.getId(), externalId, version, CHECKSUM,
                "dc:content/" + externalId));
    }

    private MigrationStageWork work(MigrationItem item, MigrationStage stage, String version) {
        return workWithPage(item, stage, version, null);
    }

    private MigrationStageWork workWithPage(MigrationItem item, MigrationStage stage, String version,
                                            Long targetPageId) {
        return new MigrationStageWork(job.getId(), item.getId(), "token", MigrationProvider.CONFLUENCE_DC,
                job.getSourceInstanceId(), job.getMode(), spaceId, stage, item.getExternalObjectId(),
                version, CHECKSUM, item.getPayloadRef(), targetPageId, 1);
    }
}
