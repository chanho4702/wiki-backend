package com.platform.wikibackend.schema;

import com.platform.wikibackend.PostgresTestSupport;
import com.platform.wikibackend.admin.AttachmentInventoryRepository;
import com.platform.wikibackend.admin.AttachmentInventoryRepository.Row;
import com.platform.wikibackend.attachment.StorageBackend;
import com.platform.wikibackend.attachment.StoredObject;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.AttachmentVersion;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.AttachmentVersionRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고아 첨부 리포트의 행 질의(엔티티 조인 ON·네이티브 not exists)가 실제 PostgreSQL에서 돌고 타입이 맞는가.
 * 컨텍스트를 공유하므로 다른 테스트 행이 있을 수 있다 — 내 행이 들어 있는지만 본다.
 */
class PostgresAttachmentInventoryTest extends PostgresTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long ALICE = 1L;

    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired AttachmentRepository attachments;
    @Autowired AttachmentVersionRepository versions;
    @Autowired AttachmentInventoryRepository inventory;

    @Test
    void 현재_첨부와_지난_버전_키를_키셋으로_읽고_주인_잃은_행_질의가_돈다() {
        Space space = spaces.save(Space.of("pgi" + SEQ.incrementAndGet(), "PG 인벤토리", null, ALICE));
        Page page = pages.save(Page.of(space.getId(), null, "문서", "본문", ALICE));
        String key = UUID.randomUUID().toString();
        Attachment a = attachments.save(Attachment.of(page.getId(), "a.png", "image/png", 3L,
                new StoredObject(StorageBackend.S3, "wiki-pg", key, null), null, ALICE));
        AttachmentVersion v = versions.save(AttachmentVersion.snapshotOf(a));

        List<Row> current = drain(true);
        List<Row> past = drain(false);

        assertThat(current).anySatisfy(r -> {
            assertThat(r.attachmentId()).isEqualTo(a.getId());
            assertThat(r.pageId()).isEqualTo(page.getId());
            assertThat(r.key()).isEqualTo(key);
            assertThat(r.backend()).isEqualTo(StorageBackend.S3);
            assertThat(r.bucket()).isEqualTo("wiki-pg");
            assertThat(r.createdAt()).isNotNull();
        });
        assertThat(past).anySatisfy(r -> {
            assertThat(r.rowId()).isEqualTo(v.getId());
            assertThat(r.attachmentId()).isEqualTo(a.getId());
            assertThat(r.pageId()).isEqualTo(page.getId());
            assertThat(r.version()).isEqualTo(1);
        });
        // PG는 FK cascade라 주인 잃은 행을 만들 수 없다 — 질의가 돌고 내 행이 끼지 않는지만 본다.
        assertThat(inventory.countPageMissing()).isGreaterThanOrEqualTo(0);
        assertThat(inventory.countAttachmentMissing()).isGreaterThanOrEqualTo(0);
        assertThat(inventory.samplePageMissing(10)).noneMatch(p -> key.equals(p.key()));
        assertThat(inventory.sampleAttachmentMissing(10)).noneMatch(p -> key.equals(p.key()));
    }

    private List<Row> drain(boolean current) {
        List<Row> all = new ArrayList<>();
        long after = 0;
        while (true) {
            List<Row> page = current ? inventory.currentRows(after) : inventory.versionRows(after);
            if (page.isEmpty()) return all;
            all.addAll(page);
            after = page.getLast().rowId();
        }
    }
}
