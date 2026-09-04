package com.platform.wikibackend.migration.repository;

import com.platform.wikibackend.migration.model.MigrationObjectMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MigrationObjectMappingRepository extends JpaRepository<MigrationObjectMapping, Long> {
    Optional<MigrationObjectMapping> findBySourceKey(String sourceKey);

    /** 이 job이 마지막으로 손댄 대상들 — 잡 마무리 링크 정리(M2)가 훑을 범위다. */
    List<MigrationObjectMapping> findByLastJobIdOrderByIdAsc(Long lastJobId);
}
