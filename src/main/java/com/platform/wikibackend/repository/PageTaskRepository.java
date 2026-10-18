package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PageTaskRepository extends JpaRepository<PageTask, Long> {

    /**
     * 단일 bulk DELETE(즉시 실행). 파생 deleteBy는 행을 읽어 지우기를 flush까지 미루고, Hibernate는
     * flush 때 INSERT를 DELETE보다 먼저 보낸다 — "지우고 다시 넣기" 교체가 같은 키를 다시 넣으면
     * 운영 PG의 유니크 제약에 걸렸다(H2 create-drop엔 제약이 없어 테스트가 못 봤다, 2026-10-18).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from PageTask t where t.pageId = :pageId")
    int deleteByPageId(@Param("pageId") Long pageId);

    List<PageTask> findByPageIdOrderByLineNoAsc(Long pageId);

    /** 내 작업 — 기한이 있는 것이 먼저(임박한 순), 없는 것은 뒤. 페이지 단위 권한은 호출부가 거른다. */
    @Query("""
            select t from PageTask t
             where t.assigneeId = :userId and t.done = :done
             order by case when t.dueDate is null then 1 else 0 end, t.dueDate asc, t.pageId asc, t.lineNo asc
            """)
    List<PageTask> findMine(@Param("userId") long userId, @Param("done") boolean done);
}
