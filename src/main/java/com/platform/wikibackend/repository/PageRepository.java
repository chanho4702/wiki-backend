package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.Page;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PageRepository extends JpaRepository<Page, Long> {
    List<Page> findBySpaceIdOrderById(Long spaceId);
    List<Page> findByParentId(Long parentId);

    // 색인 백필용 keyset 페이징 — 전량을 한 번에 메모리에 올리지 않으려는 것.
    // OFFSET이 아니라 id 커서라, 스캔 중 앞쪽 행이 지워져도 건너뛰지 않는다.
    List<Page> findByIdGreaterThanOrderByIdAsc(Long afterId, Limit limit);
    List<Page> findBySpaceIdAndIdGreaterThanOrderByIdAsc(Long spaceId, Long afterId, Limit limit);
}
