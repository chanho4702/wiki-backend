package com.platform.wikibackend.migration;

import com.platform.wikibackend.common.ConflictException;
import com.platform.wikibackend.common.NotFoundException;
import com.platform.wikibackend.migration.dto.MigrationItemEnqueueRequest;
import com.platform.wikibackend.migration.model.MigrationItem;
import com.platform.wikibackend.migration.model.MigrationJob;
import com.platform.wikibackend.migration.model.MigrationJobStatus;
import com.platform.wikibackend.migration.repository.MigrationItemRepository;
import com.platform.wikibackend.migration.repository.MigrationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원본 등록의 삽입만 담당한다. 별도 트랜잭션인 이유는 두 가지다 — job 행을 잠가 `start`와
 * 경쟁하지 않기 위해서, 그리고 동시 재시도가 unique 제약에 걸렸을 때 그 롤백이 호출자의
 * 멱등 재조회까지 무효로 만들지 않게 하기 위해서다.
 */
@Component
@RequiredArgsConstructor
public class MigrationItemIntake {

    private final MigrationJobRepository jobs;
    private final MigrationItemRepository items;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MigrationItem insert(long jobId, MigrationItemEnqueueRequest req) {
        MigrationJob job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new NotFoundException("마이그레이션 작업을 찾을 수 없습니다: " + jobId));
        if (job.getStatus() != MigrationJobStatus.PENDING) {
            throw new ConflictException("이미 시작된 job에는 원본을 추가할 수 없습니다: " + job.getStatus());
        }
        return items.saveAndFlush(MigrationItem.pending(jobId, req.externalObjectId(),
                req.sourceVersion(), req.sourceChecksum(), req.payloadRef()));
    }
}
