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

    /**
     * 접근 가능한 스페이스 전체에서 접두 일치 라벨 — 검색 화면의 라벨 자동완성이 쓴다.
     *
     * 접두 일치다(`시작%`, 부분 일치 아님): 라벨은 짧고 사용자가 앞에서부터 친다. 부분 일치로
     * 열면 인덱스를 못 타는 데다 "설계"를 치면 "재설계"까지 올라와 고르기가 어려워진다.
     *
     * 페이지 단위 제한(W18)은 여기서 보지 않는다 — 라벨 이름과 대략의 건수만 나가고 문서는
     * 드러나지 않으며, 라벨을 고른 뒤의 실제 검색이 제한을 온전히 적용한다.
     */
    @Query("""
            select l.name as name, count(l) as count
              from PageLabel l, Page p
             where l.pageId = p.id and p.spaceId in :spaceIds
               and (:prefix = '' or l.name like concat(:prefix, '%'))
             group by l.name
             order by count(l) desc, l.name asc
            """)
    List<LabelCount> suggest(@Param("spaceIds") Collection<Long> spaceIds,
                             @Param("prefix") String prefix,
                             org.springframework.data.domain.Limit limit);

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
