package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.AuditLog;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** 최신이 먼저. id를 2차 기준으로 두어 같은 시각의 기록도 순서가 흔들리지 않는다. */
    @Query("select a from AuditLog a where a.spaceId = :spaceId order by a.createdAt desc, a.id desc")
    List<AuditLog> findBySpace(@Param("spaceId") long spaceId, Limit limit);
}
