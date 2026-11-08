package com.platform.wikibackend.admin;

import com.platform.common.error.ConflictException;
import com.platform.wikibackend.admin.AttachmentInventoryRepository.PurgedRow;
import com.platform.wikibackend.admin.AttachmentInventoryRepository.Row;
import com.platform.wikibackend.admin.AttachmentOrphanReport.ObjectSample;
import com.platform.wikibackend.admin.AttachmentOrphanReport.OrphanObjects;
import com.platform.wikibackend.admin.AttachmentOrphanReport.RowGroup;
import com.platform.wikibackend.admin.AttachmentOrphanReport.RowSample;
import com.platform.wikibackend.attachment.AttachmentObjectInventory;
import com.platform.wikibackend.attachment.ListedObject;
import com.platform.wikibackend.attachment.StorageBackend;
import com.platform.wikibackend.permission.GlobalAdminGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 첨부 고아 객체 드라이런 리포트(옵시디언 49 N3) — <b>읽기만 한다</b>. 회수(삭제)는 사용자 확인 뒤 별도 작업이다.
 *
 * <p><b>고아 세 종류.</b>
 * <ul>
 *   <li>orphanObjects — 버킷에 있으나 어떤 행도 그 키를 참조하지 않는 객체. 참조는 현재 첨부
 *       ({@code attachment})와 지난 버전({@code attachment_version}) 둘 다다 — 지난 버전을 빼면
 *       "지난 버전 받기"가 껍데기가 된다. 휴지통 문서의 첨부도 복원 가능하므로 참조로 센다.</li>
 *   <li>danglingRows — 이 버킷을 가리키는 S3 행인데 객체가 없음({@code object-missing}).</li>
 *   <li>purgedPageRows — page 행이 아예 없는 첨부({@code page-missing}), 첨부 행이 없는 지난 버전
 *       ({@code attachment-missing}). 운영 PG는 FK cascade라 보통 0이고, 영구 삭제 뒤 객체 삭제가 실패한
 *       몫은 orphanObjects에 나타난다.</li>
 * </ul>
 *
 * <p><b>유예 창.</b> 업로드는 객체를 먼저 쓰고 행을 나중에 커밋한다. 그래서 막 생긴 객체는 행이 아직 안
 * 보이고, 스캔 도중 생긴 행은 객체 목록에 안 잡혔을 수 있다. 저장 키는 접두 없는 UUID라 "임시 업로드
 * 접두"로 거를 수 없으므로, 최근 {@link #GRACE} 안의 객체·행은 판정에서 뺀다.
 *
 * <p><b>예산.</b> 행 키는 메모리 맵(행 수에 비례, 수만 건 규모 가정)이고 객체는 스트리밍한다. 객체 상한
 * ({@code maxObjects})·시간 예산({@link #TIME_BUDGET}, 게이트웨이 응답 상한 10초 안)에 걸리면
 * {@code truncated}이고, 목록이 키 오름차순이므로 마지막으로 본 키 뒤의 행은 dangling 판정을 보류한다.
 */
@Service
public class AttachmentOrphanReportService {

    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 1_000;
    static final int DEFAULT_MAX_OBJECTS = 200_000;
    static final int MAX_MAX_OBJECTS = 1_000_000;
    static final Duration GRACE = Duration.ofHours(1);
    static final Duration TIME_BUDGET = Duration.ofSeconds(8);

    private static final Pattern UUID_KEY =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final GlobalAdminGuard globalAdmin;
    private final AttachmentInventoryRepository rows;
    private final ObjectProvider<AttachmentObjectInventory> inventory;

    public AttachmentOrphanReportService(GlobalAdminGuard globalAdmin, AttachmentInventoryRepository rows,
                                         ObjectProvider<AttachmentObjectInventory> inventory) {
        this.globalAdmin = globalAdmin;
        this.rows = rows;
        this.inventory = inventory;
    }

    public AttachmentOrphanReport report(long userId, Integer limitParam, Integer maxObjectsParam) {
        globalAdmin.require(userId, "첨부 고아 리포트는 전역 관리자만 볼 수 있습니다");
        AttachmentObjectInventory bucket = inventory.getIfAvailable();
        if (bucket == null) {
            throw new ConflictException("S3 첨부 저장소가 꺼져 있어 고아 객체를 조회할 수 없습니다");
        }
        int limit = clamp(limitParam, DEFAULT_LIMIT, MAX_LIMIT);
        int maxObjects = clamp(maxObjectsParam, DEFAULT_MAX_OBJECTS, MAX_MAX_OBJECTS);
        Instant scannedAt = Instant.now();
        Instant cutoff = scannedAt.minus(GRACE);
        String bucketName = bucket.bucket();

        // 1) 행 키 — 백엔드·버킷과 무관하게 모든 키를 "참조"로 둔다(보수적: 어떤 행이든 가리키면 고아 아님).
        Map<String, List<Row>> unseen = new HashMap<>();
        long rowsTotal = 0;
        long localRows = 0;
        long otherBucketRows = 0;
        for (Row row : allRows()) {
            unseen.computeIfAbsent(row.key(), k -> new ArrayList<>(1)).add(row);
            if (row.backend() != StorageBackend.S3) localRows++;
            else if (!bucketName.equals(row.bucket())) otherBucketRows++;
            else rowsTotal++;
        }

        // 2) 객체 스트리밍 — 짝이 맞으면 맵에서 지운다. 남는 것이 dangling 후보다.
        long deadline = System.nanoTime() + TIME_BUDGET.toNanos();
        long[] objectsTotal = {0};
        long[] orphanCount = {0};
        long[] orphanBytes = {0};
        long[] freshSkipped = {0};
        long[] nonUuidOrphans = {0};
        String[] lastKey = {null};
        boolean[] truncated = {false};
        List<ObjectSample> orphanSample = new ArrayList<>();
        bucket.forEachObject(o -> {
            if (objectsTotal[0] >= maxObjects || System.nanoTime() > deadline) {
                truncated[0] = true;
                return false;
            }
            objectsTotal[0]++;
            lastKey[0] = o.key();
            if (unseen.remove(o.key()) != null) return true;
            if (isFresh(o, cutoff)) {
                freshSkipped[0]++;
                return true;
            }
            orphanCount[0]++;
            orphanBytes[0] += o.size();
            if (!UUID_KEY.matcher(o.key()).matches()) nonUuidOrphans[0]++;
            if (orphanSample.size() < limit) {
                orphanSample.add(new ObjectSample(o.key(), o.size(), o.lastModified()));
            }
            return true;
        });

        // 3) dangling — 이 버킷의 S3 행 중 객체를 못 찾은 것. 유예 창 안의 행과 못 본 키 구간은 보류.
        long danglingCount = 0;
        long freshRowsSkipped = 0;
        long undeterminedRows = 0;
        List<RowSample> danglingSample = new ArrayList<>();
        List<Row> candidates = unseen.values().stream().flatMap(List::stream)
                .filter(r -> r.backend() == StorageBackend.S3 && bucketName.equals(r.bucket()))
                .sorted((a, b) -> a.key().compareTo(b.key()))
                .toList();
        for (Row r : candidates) {
            if (truncated[0] && (lastKey[0] == null || r.key().compareTo(lastKey[0]) > 0)) {
                undeterminedRows++;
                continue;
            }
            if (r.createdAt() == null || !r.createdAt().isBefore(cutoff)) {
                freshRowsSkipped++;
                continue;
            }
            danglingCount++;
            if (danglingSample.size() < limit) {
                danglingSample.add(new RowSample(r.attachmentId(), r.pageId(), r.key(), "object-missing",
                        r.version()));
            }
        }

        // 4) 주인 잃은 행 — DB만으로 판정한다.
        long purgedCount = rows.countPageMissing() + rows.countAttachmentMissing();
        List<RowSample> purgedSample = new ArrayList<>();
        for (PurgedRow p : rows.samplePageMissing(limit)) {
            purgedSample.add(new RowSample(p.attachmentId(), p.pageId(), p.key(), "page-missing", null));
        }
        if (purgedSample.size() < limit) {
            for (PurgedRow p : rows.sampleAttachmentMissing(limit - purgedSample.size())) {
                purgedSample.add(new RowSample(p.attachmentId(), null, p.key(), "attachment-missing", p.version()));
            }
        }

        List<String> notes = new ArrayList<>();
        notes.add("읽기 전용 드라이런 — 아무것도 지우지 않았다.");
        notes.add("저장 키는 접두 없는 UUID(버킷 루트)이고 임시 업로드 접두는 없다. 대신 업로드(객체 먼저)와 행 커밋 사이 "
                + "경합을 피하려 최근 " + GRACE.toMinutes() + "분 안의 객체 " + freshSkipped[0] + "건·행 "
                + freshRowsSkipped + "건을 판정에서 뺐다.");
        notes.add("참조 키 = attachment.storage_key + attachment_version.storage_key(지난 버전). "
                + "휴지통(소프트 삭제) 문서의 첨부도 복원 가능하므로 참조로 센다.");
        if (localRows > 0 || otherBucketRows > 0) {
            notes.add("LOCAL 저장소 행 " + localRows + "건, 다른 버킷을 가리키는 S3 행 " + otherBucketRows
                    + "건은 이 버킷과 대조하지 않았다(그 키는 참조로만 센다).");
        }
        if (nonUuidOrphans[0] > 0) {
            notes.add("고아 중 UUID 형식이 아닌 키 " + nonUuidOrphans[0]
                    + "건 — 위키가 쓴 객체가 아닐 수 있으니 회수 대상에서 뺄 것.");
        }
        notes.add("버전 관리 버킷이면 현재 버전만 본다(이전 버전·삭제 마커 미포함). storage_version 일치는 확인하지 않는다.");
        notes.add("purgedPageRows: 운영 PostgreSQL은 page·attachment FK가 ON DELETE CASCADE라 보통 0이다. "
                + "영구 삭제 뒤 객체 삭제가 실패한 몫은 orphanObjects에 나타난다.");
        notes.add("메모리: 행 키 전체(" + unseen.size() + "건 미매칭 포함)를 맵에 올린다 — 수만 건 규모 가정. "
                + "객체는 스트리밍, 상한 maxObjects=" + maxObjects + "·시간 " + TIME_BUDGET.toSeconds() + "초.");
        if (truncated[0]) {
            notes.add("truncated — 버킷 끝까지 보지 못했다(마지막 키 " + lastKey[0] + "). 그 뒤 구간의 행 "
                    + undeterminedRows + "건은 dangling 판정을 보류했다. 게이트웨이를 거치지 않고 다시 부르거나 "
                    + "maxObjects를 조정할 것.");
        }

        return new AttachmentOrphanReport(scannedAt, bucketName, objectsTotal[0], rowsTotal, truncated[0],
                new OrphanObjects(orphanCount[0], orphanBytes[0], orphanSample),
                new RowGroup(danglingCount, danglingSample),
                new RowGroup(purgedCount, purgedSample),
                notes);
    }

    private List<Row> allRows() {
        List<Row> all = new ArrayList<>();
        long after = 0;
        for (List<Row> page = rows.currentRows(after); !page.isEmpty(); page = rows.currentRows(after)) {
            all.addAll(page);
            after = page.getLast().rowId();
        }
        after = 0;
        for (List<Row> page = rows.versionRows(after); !page.isEmpty(); page = rows.versionRows(after)) {
            all.addAll(page);
            after = page.getLast().rowId();
        }
        return all;
    }

    private static boolean isFresh(ListedObject o, Instant cutoff) {
        return o.lastModified() == null || !o.lastModified().isBefore(cutoff);
    }

    private static int clamp(Integer value, int fallback, int max) {
        int v = Objects.requireNonNullElse(value, fallback);
        return Math.max(1, Math.min(v, max));
    }
}
