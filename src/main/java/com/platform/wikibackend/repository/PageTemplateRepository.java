package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PageTemplateRepository extends JpaRepository<PageTemplate, Long> {

    List<PageTemplate> findBySpaceIdOrderByNameAsc(Long spaceId);

    Optional<PageTemplate> findBySpaceIdAndName(Long spaceId, String name);
}
