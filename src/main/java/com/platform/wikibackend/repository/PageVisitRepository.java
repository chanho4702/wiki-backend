package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageVisit;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PageVisitRepository extends JpaRepository<PageVisit, PageVisit.Key> {

    Optional<PageVisit> findByUserIdAndPageId(Long userId, Long pageId);

    /*
     * 방문 기록 upsert(감사 W-21) — "읽고 없으면 넣기"는 같은 사용자가 처음 보는 문서를 두 탭에서 동시에 열면
     * PK 충돌로 트랜잭션 전체(조회수 증가 포함)를 롤백시켰다. 갱신 → 0행이면 ON CONFLICT DO NOTHING 삽입.
     * 경합에서 진 쪽은 앞 트랜잭션의 커밋을 기다렸다가 아무것도 하지 않는다(오류 없음).
     * ON CONFLICT DO UPDATE를 한 문장으로 쓰지 않은 이유: H2(테스트, PostgreSQL 모드)가 DO NOTHING만 받는다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "update page_visit set visited_at = current_timestamp where user_id = :userId and page_id = :pageId",
            nativeQuery = true)
    int touch(@Param("userId") long userId, @Param("pageId") long pageId);

    @Modifying(flushAutomatically = true)
    @Query(value = "insert into page_visit (user_id, page_id, visited_at) values (:userId, :pageId, current_timestamp)"
            + " on conflict do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("userId") long userId, @Param("pageId") long pageId);

    @Query("select v.pageId from PageVisit v where v.userId = :userId order by v.visitedAt desc, v.pageId desc")
    List<Long> findRecentPageIds(@Param("userId") long userId, Limit limit);

    /** 상한을 넘은 오래된 기록 — 지울 대상. 사용자 한 명의 목록이라 통째로 읽어도 짧다. */
    @Query("select v from PageVisit v where v.userId = :userId order by v.visitedAt desc, v.pageId desc")
    List<PageVisit> findAllByUser(@Param("userId") long userId);
}
