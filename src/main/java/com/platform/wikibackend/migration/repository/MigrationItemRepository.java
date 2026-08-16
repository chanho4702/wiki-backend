package com.platform.wikibackend.migration.repository;

import com.platform.wikibackend.migration.model.MigrationItem;
import com.platform.wikibackend.migration.model.MigrationItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MigrationItemRepository extends JpaRepository<MigrationItem, Long> {
    Optional<MigrationItem> findByJobIdAndSourceKey(Long jobId, String sourceKey);

    List<MigrationItem> findByJobIdAndStatusOrderByIdAsc(Long jobId, MigrationItemStatus status);
}
