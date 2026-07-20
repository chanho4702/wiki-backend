package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PageRepository extends JpaRepository<Page, Long> {
    List<Page> findBySpaceIdOrderById(Long spaceId);
    List<Page> findByParentId(Long parentId);
}
