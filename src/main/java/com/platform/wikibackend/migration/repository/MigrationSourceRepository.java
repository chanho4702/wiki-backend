package com.platform.wikibackend.migration.repository;

import com.platform.wikibackend.migration.model.MigrationSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MigrationSourceRepository extends JpaRepository<MigrationSource, Long> {
}
