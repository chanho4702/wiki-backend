package com.platform.wikibackend.migration.repository;

import com.platform.wikibackend.migration.model.MigrationObjectMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MigrationObjectMappingRepository extends JpaRepository<MigrationObjectMapping, Long> {
    Optional<MigrationObjectMapping> findBySourceKey(String sourceKey);
}
