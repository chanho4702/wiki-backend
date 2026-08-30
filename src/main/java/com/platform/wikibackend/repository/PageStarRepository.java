package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageStar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PageStarRepository extends JpaRepository<PageStar, PageStar.Key> {

    /** 별표한 순서(최근 별표가 먼저)로 페이지 id만. 화면에 쓸 메타는 페이지에서 따로 읽는다. */
    @Query("select s.pageId from PageStar s where s.userId = :userId order by s.createdAt desc, s.pageId desc")
    List<Long> findPageIds(@Param("userId") long userId);

    boolean existsByUserIdAndPageId(Long userId, Long pageId);

    void deleteByUserIdAndPageId(Long userId, Long pageId);
}
