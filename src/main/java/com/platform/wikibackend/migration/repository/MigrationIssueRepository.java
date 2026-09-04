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

    /**
     * 손실 보고서의 code별 집계. 대표 위치는 min(source_path)로 뽑는다 — 어느 것이든 하나면
     * 충분하고, 집계 쿼리 안에서 결정되므로 code마다 행을 한 번 더 읽지 않는다.
     */
    @Query("""
            select new com.platform.wikibackend.migration.report.MigrationIssueSummary(
                       s.severity, s.code, count(s), sum(s.occurrenceCount), min(s.sourcePath))
              from MigrationIssue s
             where s.jobId = :jobId
             group by s.severity, s.code
            """)
    List<MigrationIssueSummary> summarize(@Param("jobId") Long jobId);
}
