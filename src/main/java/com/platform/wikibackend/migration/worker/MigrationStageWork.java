package com.platform.wikibackend.migration.worker;

import com.platform.wikibackend.migration.model.MigrationJobMode;
import com.platform.wikibackend.migration.model.MigrationProvider;
import com.platform.wikibackend.migration.model.MigrationStage;

/**
 * 한 번의 stage 실행에 필요한 값만 담은 불변 입력이다. 엔티티를 그대로 넘기지 않으므로
 * handler는 트랜잭션 밖에서 안전하게 네트워크 I/O를 할 수 있다.
 */
public record MigrationStageWork(
        Long jobId,
        Long itemId,
        String claimToken,
        MigrationProvider provider,
        String sourceInstanceId,
        MigrationJobMode mode,
        Long targetSpaceId,
        MigrationStage stage,
        String externalObjectId,
        String sourceVersion,
        String sourceChecksum,
        String payloadRef,
        Long targetPageId,
        int attempt) {

    public boolean dryRun() {
        return mode == MigrationJobMode.DRY_RUN;
    }
}
