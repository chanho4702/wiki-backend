package com.platform.wikibackend.importapi.dto;

import com.platform.wikibackend.domain.PageType;

import java.time.Instant;
import java.util.List;

/**
 * 내부 import API의 응답 본문 모음(W29 X1, 설계 §2).
 * 계약 예시는 `src/test/resources/fixtures/import-api/`에 있다.
 */
public final class WikiImportResponses {

    private WikiImportResponses() {
    }

    /**
     * 쓰기 과정의 손실 한 건. 엔진이 자기 보고서에 옮겨 적는다 — 여기서 버리면 "옮겼는데
     * 제목이 잘렸다"를 아무도 모른다. sourcePath는 엔진이 이미 알고 있어(자기가 보낸 항목이다)
     * 싣지 않는다.
     */
    public record Issue(String severity, String code) {
    }

    /** POST /pages, PUT /pages/{id} */
    public record PageWritten(long pageId, int version, List<Issue> issues) {
    }

    /** PUT /pages/{id}/content — changed=false면 본문이 이미 같아 아무것도 하지 않았다. */
    public record ContentWritten(long pageId, int version, boolean changed) {
    }

    /** PUT /pages/{id}/order */
    public record OrderWritten(long pageId, long sortOrder, boolean changed) {
    }

    /**
     * POST /pages/{id}/attachments.
     *
     * outcome: CREATED(새 첨부) / NEW_VERSION(같은 이름·다른 내용 → 같은 id로 갈아끼움) /
     * UNCHANGED(같은 이름·같은 checksum → 아무것도 하지 않음). 본문 참조가 첨부 id로 걸리므로
     * 갈아끼우기가 새 행을 만드는 것보다 안전하다(W23 규칙 그대로).
     */
    public record AttachmentRegistered(long attachmentId, String inlineUrl, String downloadUrl,
                                       String outcome) {
    }

    /** POST /pages/{id}/comments */
    public record CommentWritten(long commentId) {
    }

    /** GET /comments/{id} — 재실행 때 "사람이 지운 댓글"을 가려내려고 존재만 확인한다. */
    public record CommentView(long commentId, long pageId, Long parentCommentId, Instant createdAt) {
    }

    /** GET /pages/{id} — 이관 검증(VERIFY)이 읽는 요약. 본문은 길이만 준다. */
    public record PageView(long pageId, long spaceId, Long parentId, String title, PageType type,
                           int contentLength, int version, long sortOrder, List<String> labels,
                           List<AttachmentView> attachments, long commentCount) {
    }

    public record AttachmentView(long id, String filename, String checksum) {
    }

    /** GET /spaces/{id}/pages?title= — 제목이 여럿에 걸리면 여러 건이 온다(엔진이 모호로 보고). */
    public record PageMatches(List<PageMatch> pages) {
    }

    public record PageMatch(long pageId, String title, PageType type) {
    }

    /** GET /spaces/{id} */
    public record SpaceView(long spaceId, String key, String name) {
    }
}
