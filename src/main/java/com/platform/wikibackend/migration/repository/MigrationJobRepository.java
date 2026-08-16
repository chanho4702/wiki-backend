package com.platform.wikibackend.migration.repository;

import com.platform.wikibackend.migration.model.MigrationJob;
import com.platform.wikibackend.migration.model.MigrationJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MigrationJobRepository extends JpaRepository<MigrationJob, Long> {
    List<MigrationJob> findByStatusOrderByCreatedAtAscIdAsc(MigrationJobStatus status);
}
