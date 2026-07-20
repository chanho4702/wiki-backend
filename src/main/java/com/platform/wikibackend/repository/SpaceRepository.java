package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Space;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpaceRepository extends JpaRepository<Space, Long> {
    boolean existsByKey(String key);
}
