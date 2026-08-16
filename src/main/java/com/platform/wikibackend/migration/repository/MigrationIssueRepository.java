package com.platform.wikibackend.migration.repository;

import com.platform.wikibackend.migration.model.MigrationIssue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MigrationIssueRepository extends JpaRepository<MigrationIssue, Long> {
    List<MigrationIssue> findByJobIdOrderByIdAsc(Long jobId);

    Optional<MigrationIssue> findByItemIdAndIssueKey(Long itemId, String issueKey);
}
