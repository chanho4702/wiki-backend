package com.platform.wikibackend.importapi.dto;

import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.permission.dto.RestrictionPrincipal;

import java.time.Instant;
import java.util.List;

/**
 * 내부 import API의 요청 본문 모음(W29 X1, 설계 §2).
 *
 * 한 파일에 모아 둔 이유는 이것이 **한 벌의 계약**이기 때문이다 — migration-service가 이 표를
 * 그대로 보고 가짜 위키 서버를 만든다. 파일이 흩어지면 필드 하나가 조용히 갈라진다.
 * 계약 예시는 `src/test/resources/fixtures/import-api/`에 있다.
 *
 * 모든 시각은 ISO-8601(오프셋 포함) 문자열이다. null 허용 필드는 "원본이 알려 주지 않았다"이지
 * "기본값을 써라"가 아니다 — 서버가 정하는 폴백은 각 필드 주석에 적는다.
 */
public final class WikiImportRequests {

    private WikiImportRequests() {
    }

    /**
     * POST /pages — 새 문서.
     *
     * authorId가 있으면 그 사람이 쓴 문서가 되고, 없으면 `X-Actor-Id`(잡 요청자)가 작성자로
     * 눕고 importedAuthorName·sourceUrl이 "이관됨 · {원본 이름}" 표시로 남는다(V36).
     * revisions가 오면 그 개수 k만큼 리비전 1..k를 깔고 현재본이 k+1이 된다.
     */
    public record CreatePage(Long spaceId,
                             Long parentId,
                             PageType type,
                             String title,
                             String content,
                             Instant createdAt,
                             Instant updatedAt,
                             Long authorId,
                             String importedAuthorName,
                             String sourceUrl,
                             Integer sortOrder,
                             List<String> labels,
                             List<Revision> revisions) {
    }

    /**
     * 지난 버전 하나. version은 **순서를 정할 때만** 쓰인다 — 실제 리비전 번호는 서버가 1부터
     * 다시 매긴다(원본 번호를 그대로 쓰면 우리 쪽 "현재 버전 = 리비전 최대 번호" 불변식이 깨진다).
     */
    public record Revision(Integer version,
                           String title,
                           String content,
                           Long editorId,
                           String editorName,
                           Instant savedAt,
                           String changeNote) {
    }

    /**
     * PUT /pages/{id} — 재이관. 새 리비전 1건이 쌓이고 updatedAt은 원본 것으로 되돌아간다.
     *
     * sourceUrl은 §2 표에 없는 선택 필드다. 넣지 않으면 재이관 때 미대조 문서의 원본 주소가
     * 지워진다(작성자 표시가 이름만 남아 확인할 길이 사라진다).
     */
    public record ReimportPage(String title,
                               String content,
                               Instant updatedAt,
                               Long editorId,
                               String editorName,
                               String changeNote,
                               String sourceUrl,
                               List<String> labels) {
    }

    /**
     * PUT /pages/{id}/content — 본문만 교체.
     *
     * bumpVersion=false(기본)면 버전을 올리지 않고 현재 리비전 본문까지 함께 눌러 이력과
     * 현재를 일치시킨다(첨부 URL fixup). true면 changeNote를 단 새 리비전이 쌓인다(링크 정리).
     */
    public record RewriteContent(String content, Boolean bumpVersion, String changeNote) {
    }

    /** PUT /pages/{id}/order — 형제 순번만. 본문이 그대로라 리비전도 색인 이벤트도 없다. */
    public record Reorder(Integer sortOrder) {
    }

    /**
     * POST /pages/{id}/comments — 원본 댓글.
     *
     * authorId가 있으면 그 사람의 댓글이 되고, 없으면 `X-Actor-Id`가 작성자로 눕고 authorName이
     * 표시 이름 스냅샷으로 남는다. 알림도 자동 구독도 없다.
     */
    public record CreateComment(Long parentCommentId,
                                Long authorId,
                                String authorName,
                                String body,
                                Instant createdAt) {
    }

    /**
     * PUT /pages/{id}/restrictions — 페이지 제한 통째 교체.
     *
     * 권한 검사·감사·자기 잠금 방지가 없다. fail-closed(대조 실패 시 요청자 단독) 판단은 이미
     * 엔진이 끝낸 상태로 온다 — 위키는 받은 목록을 그대로 건다.
     */
    public record ReplaceRestrictions(List<RestrictionPrincipal> view, List<RestrictionPrincipal> edit) {
    }
}
