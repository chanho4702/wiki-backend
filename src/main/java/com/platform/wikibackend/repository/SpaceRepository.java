package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Space;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpaceRepository extends JpaRepository<Space, Long> {
    boolean existsByKey(String key);

    /**
     * 트리 이동/재정렬의 직렬화 앵커 — 같은 스페이스의 동시 move가 형제 순번을 겹치게 만들지
     * 않도록 스페이스 행을 잠근다(ALM의 프로젝트 행 잠금과 같은 역할).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Space s where s.id = :id")
    Optional<Space> findByIdForUpdate(@Param("id") Long id);
}
