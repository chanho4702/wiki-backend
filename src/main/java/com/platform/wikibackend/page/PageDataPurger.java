package com.platform.wikibackend.page;

import com.platform.wikibackend.attachment.AttachmentStorageRouter;
import com.platform.wikibackend.attachment.StorageBackend;
import com.platform.wikibackend.domain.CollaborationDraftMetadata;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 되돌릴 수 없는 삭제의 "딸린 데이터" 정리 — 휴지통 영구 삭제와 스페이스 삭제가 함께 쓴다.
 *
 * 왜 한 곳인가: 두 경로가 비슷한 목록을 각자 들고 있다가 서로 어긋났다. 영구 삭제는 지난 첨부 버전
 * 객체를 남겼고, 스페이스 삭제는 그것에 더해 리액션까지 남겼고, 둘 다 공동 편집 상태(Yjs, room 키라
 * page FK가 없다)를 지우지 않았다(감사 W-05). 테이블이 하나 늘 때 고칠 곳이 하나여야 한다.
 *
 * 질의는 테이블당 bulk DELETE 한 번(묶음 단위)이다 — 페이지마다 질의 여러 번을 돌리던 N+1을 없앴다(W-15).
 * 저장소 객체는 커밋 뒤에 지운다(롤백되면 남는다 — DB 행이 살아 있으니 그게 맞다).
 *
 * page 행 자체는 호출부가 지운다(휴지통 포함 네이티브 삭제는 각자 사정이 다르다). 감사 기록은 남긴다 —
 * "누가 언제 무엇을 지웠나"가 삭제보다 오래 살아야 한다(V30).
 *
 * PG에서는 page FK cascade가 상당수를 대신 지우지만, 명시적으로 지운다: H2 테스트 스키마에는 FK가 없고,
 * 리액션·공동 편집 상태처럼 FK가 아예 없는 표가 있다.
 */
@Component
@RequiredArgsConstructor
public class PageDataPurger {

    /** IN 절 한 번에 싣는 id 수 — PG 바인드 파라미터 한도(32767)보다 넉넉히 작게. */
    static final int CHUNK = 500;

    @PersistenceContext
    private EntityManager em;
    private final AttachmentStorageRouter storage;

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeDependents(Collection<Long> pageIds) {
        List<Long> all = List.copyOf(pageIds);
        for (int from = 0; from < all.size(); from += CHUNK) {
            purgeChunk(all.subList(from, Math.min(all.size(), from + CHUNK)));
        }
    }

    private void purgeChunk(List<Long> ids) {
        // 1) 저장소 객체 — 현재 첨부와 지난 버전 둘 다. 행을 지우기 전에 키를 읽는다.
        List<Object[]> objects = new ArrayList<>();
        objects.addAll(em.createQuery("""
                select a.storageBackend, a.storageBucket, a.storageKey, a.storageVersion
                from Attachment a where a.pageId in :ids""", Object[].class)
                .setParameter("ids", ids).getResultList());
        objects.addAll(em.createQuery("""
                select v.storageBackend, v.storageBucket, v.storageKey, v.storageVersion
                from AttachmentVersion v
                where v.attachmentId in (select a.id from Attachment a where a.pageId in :ids)""", Object[].class)
                .setParameter("ids", ids).getResultList());
        for (Object[] o : objects) {
            storage.deleteAfterCommit((StorageBackend) o[0], (String) o[1], (String) o[2], (String) o[3]);
        }

        // 2) 행 — FK가 없는 것(리액션·버전·공동 편집)부터, 부모 행이 사라지기 전에.
        bulk("""
                delete from AttachmentVersion v
                where v.attachmentId in (select a.id from Attachment a where a.pageId in :ids)""", ids);
        bulk("""
                delete from Reaction r where r.targetType = 'COMMENT'
                and r.targetId in (select c.id from PageComment c where c.pageId in :ids)""", ids);
        bulk("delete from Reaction r where r.targetType = 'PAGE' and r.targetId in :ids", ids);
        em.createQuery("delete from CollaborationDraftMetadata d where d.room in :rooms")
                .setParameter("rooms", ids.stream().map(CollaborationDraftMetadata::room).toList())
                .executeUpdate();
        bulk("delete from Attachment a where a.pageId in :ids", ids);
        bulk("delete from PageRevision r where r.pageId in :ids", ids);
        bulk("delete from PageComment c where c.pageId in :ids", ids);
        bulk("delete from PageTask t where t.pageId in :ids", ids);
        bulk("delete from PageRestriction r where r.pageId in :ids", ids);
        bulk("delete from PageLabel l where l.pageId in :ids", ids);
        bulk("delete from PageLink l where l.sourcePageId in :ids", ids);
        bulk("delete from PageWatch w where w.pageId in :ids", ids);
        bulk("delete from PageVisit v where v.pageId in :ids", ids);
        bulk("delete from PageStar s where s.pageId in :ids", ids);
        bulk("delete from Notification n where n.pageId in :ids", ids);
    }

    private void bulk(String jpql, List<Long> ids) {
        em.createQuery(jpql).setParameter("ids", ids).executeUpdate();
    }
}
