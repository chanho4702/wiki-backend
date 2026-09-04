package com.platform.wikibackend.migration.confluence.dc;

import com.platform.wikibackend.migration.MigrationItemIntake;
import com.platform.wikibackend.migration.dto.MigrationDiscoverResponse;
import com.platform.wikibackend.migration.dto.MigrationItemEnqueueRequest;
import com.platform.wikibackend.migration.model.MigrationItem;
import com.platform.wikibackend.migration.model.MigrationSource;
import com.platform.wikibackend.migration.repository.MigrationItemRepository;
import com.platform.wikibackend.migration.repository.MigrationSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 원본 스페이스의 페이지를 훑어 job의 처리 대기열에 담는다.
 *
 * 담는 순서가 중요하다. RESOLVE에서 부모의 대상 페이지 id를 object map에서 찾는데, 부모가 아직
 * 안 만들어졌으면 문서가 루트로 떨어진다(WARNING PARENT_NOT_FOUND). 그래서 조상 깊이 오름차순으로
 * 담고, worker는 item id 순으로 집으므로 부모가 항상 먼저 처리된다. 같은 깊이 안의 순서는 원본이
 * 알려주지 않아 id 오름차순으로 고정한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfluenceDcDiscoveryService {

    private final ConfluenceDcClient client;
    private final ConfluenceDcProperties properties;
    private final MigrationSourceRepository sources;
    private final MigrationItemRepository items;
    private final MigrationItemIntake intake;

    /**
     * 이 job의 원본을 다시 훑어 새 항목만 담는다. 이미 담긴 항목은 건드리지 않으므로 몇 번을
     * 눌러도 결과가 같다(멱등).
     */
    public MigrationDiscoverResponse discover(long jobId, Instant now) {
        MigrationSource source = sources.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("이 작업에는 원본 정보가 없습니다"));
        ConfluenceDcCredentials credentials = new ConfluenceDcCredentials(
                source.getBaseUrl(), source.getSpaceKey(), source.getAuthToken());

        List<ConfluenceContentSummary> discovered = fetchAll(credentials);
        discovered.sort(Comparator.comparingInt(ConfluenceContentSummary::depth)
                .thenComparing(idOrder()));

        int enqueued = 0;
        int skipped = 0;
        for (ConfluenceContentSummary page : discovered) {
            if (enqueue(jobId, page)) {
                enqueued++;
            } else {
                skipped++;
            }
        }
        String spaceName = client.probe(credentials).spaceName();
        recordDiscovery(jobId, discovered.size(), spaceName, now);
        log.info("컨플루언스 DC 발견 완료: job={} 발견={} 신규={} 기존={}",
                jobId, discovered.size(), enqueued, skipped);
        return new MigrationDiscoverResponse(discovered.size(), enqueued, skipped);
    }

    private List<ConfluenceContentSummary> fetchAll(ConfluenceDcCredentials credentials) {
        List<ConfluenceContentSummary> all = new ArrayList<>();
        int start = 0;
        while (true) {
            ConfluenceContentPage page = client.listPages(credentials, start);
            all.addAll(page.results());
            if (all.size() >= properties.maxPages()) {
                // 상한에서 자른다. 소리 없이 절반만 옮기는 것보다, 상한에 닿았다는 사실이 로그에
                // 남고 관리자가 상한을 올리거나 스페이스를 쪼개는 편이 낫다.
                log.warn("원본 페이지가 상한({})을 넘어 잘랐다 — platform.wiki.migration.dc.max-pages",
                        properties.maxPages());
                return new ArrayList<>(all.subList(0, properties.maxPages()));
            }
            if (!page.hasMore() || page.results().isEmpty()) {
                return all;
            }
            start += page.results().size();
        }
    }

    /** 새로 담았으면 true, 이미 있어 건너뛰었으면 false. */
    private boolean enqueue(long jobId, ConfluenceContentSummary page) {
        String sourceKey = MigrationItem.sourceKeyFor(page.id());
        if (items.findByJobIdAndSourceKey(jobId, sourceKey).isPresent()) {
            return false;
        }
        MigrationItemEnqueueRequest request = new MigrationItemEnqueueRequest(
                page.id(), String.valueOf(page.version()), checksumOf(page), "dc:content/" + page.id());
        try {
            intake.insert(jobId, request);
            return true;
        } catch (DataIntegrityViolationException e) {
            // 같은 job에 두 발견이 겹쳤다. unique 제약이 이겼으니 이쪽은 건너뛴 것으로 센다.
            return false;
        }
    }

    /**
     * 발견 기록은 순회가 끝난 뒤 한 번만 쓴다 — 원본을 훑는 내내 행을 잠그고 있으면 상태 조회가
     * 그 시간만큼 막힌다. 리포지토리 자체 트랜잭션(merge)으로 충분하다.
     */
    private void recordDiscovery(long jobId, int discoveredCount, String spaceName, Instant now) {
        sources.findById(jobId).ifPresent(source -> {
            source.recordDiscovery(discoveredCount, spaceName, now);
            sources.save(source);
        });
    }

    /** enqueue 때의 원본 상태 지문. 버전이 오르면 값이 달라져 재이관이 갱신으로 이어진다. */
    static String checksumOf(ConfluenceContentSummary page) {
        return sha256(page.id() + ":" + page.version());
    }

    /** id는 숫자 문자열이지만 사이트에 따라 아닐 수 있어, 숫자면 숫자로 아니면 사전순으로 센다. */
    private static Comparator<ConfluenceContentSummary> idOrder() {
        return Comparator.comparing(summary -> {
            try {
                return String.format("%020d", Long.parseLong(summary.id()));
            } catch (NumberFormatException exception) {
                return summary.id();
            }
        });
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
