package com.platform.wikibackend.admin;

import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.attachment.StorageBackend;
import com.platform.wikibackend.attachment.StoredObject;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.AttachmentVersion;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.AttachmentVersionRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 첨부 고아 객체 드라이런 리포트(옵시디언 49 N3) — 읽기만 하고 아무것도 지우지 않는다.
 *
 * 세 종류를 구분한다: 버킷에만 있는 객체(orphanObjects), 행만 있고 객체가 없는 것(danglingRows),
 * 페이지가 사라졌는데 남은 행(purgedPageRows). 막 올라온 업로드(객체가 행보다 먼저 생긴다)를
 * 고아로 잡지 않는 유예 창이 핵심 안전장치다.
 */
@SpringBootTest
@ActiveProfiles("test")
class AttachmentOrphanReportApiTest {

    private static final long ADMIN = 1L;
    private static final long MEMBER = 2L;
    private static final String PATH = "/api/wiki/admin/attachments/orphans";
    private static final String BUCKET = "wiki-test";
    private static final Instant OLD = Instant.now().minus(2, ChronoUnit.HOURS);

    @TestConfiguration
    static class Inventory {
        @Bean
        InMemoryObjectInventory inMemoryObjectInventory() {
            return new InMemoryObjectInventory(BUCKET);
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired AttachmentRepository attachments;
    @Autowired AttachmentVersionRepository versions;
    @Autowired FakePermissionClient perms;
    @Autowired InMemoryObjectInventory bucket;

    MockMvc mvc;
    Page page;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        versions.deleteAllInBatch();
        attachments.deleteAllInBatch();
        TestPages.deleteAll(jdbc);
        spaces.deleteAllInBatch();
        bucket.clear();
        perms.reset();
        perms.allowAll(ADMIN);
        Space space = spaces.save(Space.of("or" + (System.nanoTime() % 100000), "고아", null, ADMIN));
        page = pages.save(Page.of(space.getId(), null, "문서", "본문", ADMIN));
    }

    @Test
    void 세_종류의_고아를_구분해_센다() throws Exception {
        Attachment paired = s3Row(page.getId(), "k-paired");
        bucket.put("k-paired", 100, OLD);
        // 지난 버전 객체는 attachment_version이 참조한다 — 이것을 고아로 세면 "지난 버전 받기"가 깨진다.
        AttachmentVersion snapshot = versions.save(AttachmentVersion.snapshotOf(paired));
        jdbc.update("update attachment_version set storage_key = 'k-ver', created_at = ? where id = ?",
                Timestamp.from(OLD), snapshot.getId());
        bucket.put("k-ver", 50, OLD);

        Attachment dangling = s3Row(page.getId(), "k-dangling");
        // 막 만든 행은 객체 업로드와 커밋 사이일 수 있다 — 유예 창 안이라 dangling이 아니다.
        attachments.save(Attachment.of(page.getId(), "new.png", "image/png", 1L,
                new StoredObject(StorageBackend.S3, BUCKET, "k-new-row", null), null, ADMIN));
        // 다른 저장소·다른 버킷의 행은 이 버킷과 대조하지 않는다.
        attachments.save(Attachment.of(page.getId(), "l.png", "image/png", 1L,
                new StoredObject(StorageBackend.LOCAL, null, "k-local", null), null, ADMIN));
        s3Row(page.getId(), "k-other", "old-bucket");

        bucket.put("k-orphan", 10, OLD);
        // 막 올라온 객체 — 행 커밋 전일 수 있어 고아로 세지 않는다.
        bucket.put("k-fresh", 7, Instant.now());

        // 영구 삭제된 페이지에 남은 행 — H2에는 FK가 없어 페이지 행만 지워 재현한다.
        Page gone = pages.save(Page.of(page.getSpaceId(), null, "사라진 문서", "본문", ADMIN));
        Attachment purged = s3Row(gone.getId(), "k-purged");
        bucket.put("k-purged", 30, OLD);
        jdbc.update("delete from page where id = ?", gone.getId());

        mvc.perform(get(PATH).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket").value(BUCKET))
                .andExpect(jsonPath("$.scannedAt").exists())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.objectsTotal").value(5))
                // 이 버킷을 가리키는 S3 행: paired·dangling·k-new-row·purged + 지난 버전 1
                .andExpect(jsonPath("$.rowsTotal").value(5))
                .andExpect(jsonPath("$.orphanObjects.count").value(1))
                .andExpect(jsonPath("$.orphanObjects.bytes").value(10))
                .andExpect(jsonPath("$.orphanObjects.sample[0].key").value("k-orphan"))
                .andExpect(jsonPath("$.orphanObjects.sample[0].size").value(10))
                .andExpect(jsonPath("$.orphanObjects.sample[0].lastModified").exists())
                .andExpect(jsonPath("$.danglingRows.count").value(1))
                .andExpect(jsonPath("$.danglingRows.sample[0].attachmentId").value(dangling.getId()))
                .andExpect(jsonPath("$.danglingRows.sample[0].pageId").value(page.getId()))
                .andExpect(jsonPath("$.danglingRows.sample[0].key").value("k-dangling"))
                .andExpect(jsonPath("$.danglingRows.sample[0].reason").value("object-missing"))
                .andExpect(jsonPath("$.purgedPageRows.count").value(1))
                .andExpect(jsonPath("$.purgedPageRows.sample[0].attachmentId").value(purged.getId()))
                .andExpect(jsonPath("$.purgedPageRows.sample[0].pageId").value(gone.getId()))
                .andExpect(jsonPath("$.purgedPageRows.sample[0].key").value("k-purged"))
                .andExpect(jsonPath("$.purgedPageRows.sample[0].reason").value("page-missing"))
                .andExpect(jsonPath("$.notes").isArray());
    }

    @Test
    void 지난_버전_객체가_없으면_dangling으로_센다() throws Exception {
        Attachment current = s3Row(page.getId(), "k-cur");
        bucket.put("k-cur", 1, OLD);
        AttachmentVersion snapshot = versions.save(AttachmentVersion.snapshotOf(current));
        jdbc.update("update attachment_version set storage_key = 'k-ver-gone', created_at = ? where id = ?",
                Timestamp.from(OLD), snapshot.getId());

        mvc.perform(get(PATH).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.danglingRows.count").value(1))
                .andExpect(jsonPath("$.danglingRows.sample[0].attachmentId").value(current.getId()))
                .andExpect(jsonPath("$.danglingRows.sample[0].key").value("k-ver-gone"))
                .andExpect(jsonPath("$.danglingRows.sample[0].version").value(1));
    }

    @Test
    void 전역_관리자가_아니면_403이다() throws Exception {
        for (WikiAction action : WikiAction.values()) {
            perms.allow(MEMBER, page.getSpaceId(), action);
        }

        mvc.perform(get(PATH).with(asUser(MEMBER, "Bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 샘플은_기본_200개_최대_1000개다() throws Exception {
        for (int i = 0; i < 1005; i++) {
            bucket.put(String.format("o-%05d", i), 1, OLD);
        }

        mvc.perform(get(PATH).with(asUser(ADMIN, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orphanObjects.count").value(1005))
                .andExpect(jsonPath("$.orphanObjects.sample", hasSize(200)));

        mvc.perform(get(PATH).param("limit", "5000").with(asUser(ADMIN, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orphanObjects.count").value(1005))
                .andExpect(jsonPath("$.orphanObjects.bytes").value(1005))
                .andExpect(jsonPath("$.orphanObjects.sample", hasSize(1000)));

        mvc.perform(get(PATH).param("limit", "3").with(asUser(ADMIN, "Alice")))
                .andExpect(jsonPath("$.orphanObjects.sample", hasSize(3)));
    }

    /**
     * 객체 상한에 걸리면 truncated다. 끝까지 못 본 키 구간의 행은 객체가 없다고 단정할 수 없으므로
     * dangling으로 세지 않는다(S3 목록은 키 오름차순).
     */
    @Test
    void 객체_상한을_넘으면_truncated이고_못_본_구간은_판정하지_않는다() throws Exception {
        bucket.put("a-1", 1, OLD);
        bucket.put("a-2", 1, OLD);
        bucket.put("a-3", 1, OLD);
        s3Row(page.getId(), "a-0");   // 본 구간 안 — 객체 없음이 확정이다
        s3Row(page.getId(), "z-9");   // 못 본 구간 — 판정 보류

        mvc.perform(get(PATH).param("maxObjects", "2").with(asUser(ADMIN, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.objectsTotal").value(2))
                .andExpect(jsonPath("$.orphanObjects.count").value(2))
                .andExpect(jsonPath("$.danglingRows.count").value(1))
                .andExpect(jsonPath("$.danglingRows.sample[0].key").value("a-0"));
    }

    private Attachment s3Row(Long pageId, String key) {
        return s3Row(pageId, key, BUCKET);
    }

    /** 유예 창 밖으로 — @CreationTimestamp가 INSERT에서 "지금"으로 덮으므로 저장 뒤 SQL로 되돌린다. */
    private Attachment s3Row(Long pageId, String key, String bucketName) {
        Attachment saved = attachments.save(Attachment.of(pageId, key + ".png", "image/png", 1L,
                new StoredObject(StorageBackend.S3, bucketName, key, null), null, ADMIN));
        jdbc.update("update attachment set created_at = ? where id = ?", Timestamp.from(OLD), saved.getId());
        return saved;
    }
}
