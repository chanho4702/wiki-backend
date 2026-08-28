package com.platform.wikibackend.repository;

import com.platform.wikibackend.domain.PageLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PageLabelRepository extends JpaRepository<PageLabel, Long> {

    List<PageLabel> findByPageIdOrderByName(Long pageId);

    List<PageLabel> findByPageIdIn(Collection<Long> pageIds);

    void deleteByPageId(Long pageId);

    /**
     * 스페이스의 라벨과 사용 횟수. Page 조인이라 휴지통 페이지는 @SQLRestriction이 알아서 뺀다 —
     * 버린 문서의 라벨이 목록에 남으면 클릭했을 때 빈 결과가 나온다.
     */
    @Query("""
            select l.name as name, count(l) as count
              from PageLabel l, Page p
             where l.pageId = p.id and p.spaceId = :spaceId
             group by l.name
             order by count(l) desc, l.name asc
            """)
    List<LabelCount> countBySpaceId(@Param("spaceId") Long spaceId);

    @Query("""
            select p.id from PageLabel l, Page p
             where l.pageId = p.id and p.spaceId = :spaceId and l.name = :name
            """)
    List<Long> findPageIdsBySpaceIdAndName(@Param("spaceId") Long spaceId, @Param("name") String name);

    interface LabelCount {
        String getName();
        long getCount();
    }
}
