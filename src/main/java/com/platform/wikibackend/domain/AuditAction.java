package com.platform.wikibackend.domain;

/**
 * 기록하는 조작.
 *
 * **되돌리기 어렵거나 접근 범위를 바꾸는 것만** 넣는다. 본문 수정은 리비전이 이미 남기고,
 * 모든 조회까지 기록하면 감사 로그가 아니라 활동 추적이 되어 보존 정책 논의가 따라붙는다.
 *
 * 스페이스 삭제는 없다. 기록이 스페이스 스코프라 삭제와 함께 사라져서, 남겨도 읽을 곳이 없다 —
 * 그건 전역 감사 로그가 생겨야 담을 수 있다.
 */
public enum AuditAction {
    PAGE_TRASHED,
    PAGE_RESTORED,
    PAGE_PURGED,
    PAGE_ARCHIVED,
    PAGE_UNARCHIVED,
    PAGE_RESTRICTIONS_CHANGED,
    ATTACHMENT_DELETED,
    SPACE_UPDATED,
    TEMPLATE_CREATED,
    TEMPLATE_UPDATED,
    TEMPLATE_DELETED
}
