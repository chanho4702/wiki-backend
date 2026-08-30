package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PageTaskRepository extends JpaRepository<PageTask, Long> {

    void deleteByPageId(Long pageId);

    List<PageTask> findByPageIdOrderByLineNoAsc(Long pageId);

    /** 내 작업 — 기한이 있는 것이 먼저(임박한 순), 없는 것은 뒤. 페이지 단위 권한은 호출부가 거른다. */
    @Query("""
            select t from PageTask t
             where t.assigneeId = :userId and t.done = :done
             order by case when t.dueDate is null then 1 else 0 end, t.dueDate asc, t.pageId asc, t.lineNo asc
            """)
    List<PageTask> findMine(@Param("userId") long userId, @Param("done") boolean done);
}
