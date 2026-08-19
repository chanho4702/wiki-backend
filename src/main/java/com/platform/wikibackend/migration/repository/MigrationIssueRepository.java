package com.platform.wikibackend.migration.repository;

import com.platform.wikibackend.migration.model.MigrationIssue;
import com.platform.wikibackend.migration.report.MigrationIssueSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MigrationIssueRepository extends JpaRepository<MigrationIssue, Long> {
    List<MigrationIssue> findByJobIdOrderByIdAsc(Long jobId);

    Optional<MigrationIssue> findByItemIdAndIssueKey(Long itemId, String issueKey);

    @Query("""
            select new com.platform.wikibackend.migration.report.MigrationIssueSummary(
                       s.severity, s.code, count(s), sum(s.occurrenceCount))
              from MigrationIssue s
             where s.jobId = :jobId
             group by s.severity, s.code
            """)
    List<MigrationIssueSummary> summarize(@Param("jobId") Long jobId);
}
