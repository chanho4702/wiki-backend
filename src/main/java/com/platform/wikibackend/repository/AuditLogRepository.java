package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.AuditLog;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** 최신이 먼저. id를 2차 기준으로 두어 같은 시각의 기록도 순서가 흔들리지 않는다. */
    @Query("select a from AuditLog a where a.spaceId = :spaceId order by a.createdAt desc, a.id desc")
    List<AuditLog> findBySpace(@Param("spaceId") long spaceId, Limit limit);

    /** 전역 목록 — 스페이스 삭제처럼 스페이스 안에서는 읽을 수 없는 기록. */
    @Query("select a from AuditLog a where a.action = :action order by a.createdAt desc, a.id desc")
    List<AuditLog> findByAction(@Param("action") String action, Limit limit);

    /*
     * 전역 감사 피드(관리자 대시보드 "최근 활동").
     *
     * 필터 두 개(type·since)를 nullable 파라미터 하나의 질의로 합치지 않는다 — `:action is null`
     * 형태는 Postgres에서 파라미터 타입을 못 정해 터지는 경로가 있다. 대신 since는 없을 때
     * Instant.EPOCH으로 채우고(created_at은 not null이라 전건이 걸린다) action 유무로만 갈라
     * 파생 질의 두 개를 쓴다. 정렬은 Pageable이 들고 온다.
     */
    Page<AuditLog> findByCreatedAtGreaterThanEqual(Instant since, Pageable pageable);

    Page<AuditLog> findByActionAndCreatedAtGreaterThanEqual(String action, Instant since, Pageable pageable);
}
