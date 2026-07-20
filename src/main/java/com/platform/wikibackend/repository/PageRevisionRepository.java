package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PageRevisionRepository extends JpaRepository<PageRevision, Long> {
    List<PageRevision> findByPageIdOrderByVersionDesc(Long pageId);
    Optional<PageRevision> findByPageIdAndVersion(Long pageId, Integer version);
    void deleteByPageId(Long pageId);
}
