package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Reaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ReactionRepository extends JpaRepository<Reaction, Reaction.Key> {

    /** 여러 대상을 한 번에 — 댓글 목록에 리액션을 붙일 때 댓글마다 묻지 않는다. */
    @Query("select r from Reaction r where r.targetType = :type and r.targetId in :ids")
    List<Reaction> findAllFor(@Param("type") String type, @Param("ids") Collection<Long> ids);

    void deleteByTargetTypeAndTargetId(String targetType, Long targetId);
}
