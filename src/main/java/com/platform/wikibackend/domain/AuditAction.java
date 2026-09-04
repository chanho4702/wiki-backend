package com.platform.wikibackend.domain;

/**
 * 기록하는 조작.
 *
 * **되돌리기 어렵거나 접근 범위를 바꾸는 것만** 넣는다. 본문 수정은 리비전이 이미 남기고,
 * 모든 조회까지 기록하면 감사 로그가 아니라 활동 추적이 되어 보존 정책 논의가 따라붙는다.
 *
 * SPACE_DELETED는 스페이스 스코프 화면에서는 볼 수 없다(스페이스가 없다) — 전역 관리자의
 * "스페이스 삭제 기록" 목록이 읽는다. V30에서 space FK를 풀어 기록이 스페이스보다 오래 남는다.
 */
public enum AuditAction {
    PAGE_TRASHED,
    PAGE_RESTORED,
    PAGE_PURGED,
    PAGE_ARCHIVED,
    PAGE_UNARCHIVED,
    PAGE_RESTRICTIONS_CHANGED,
    // 소유자·검증(W27-5)은 되돌리기 어렵지는 않지만 "누가 이 문서를 맞다고 했나"의 근거다 —
    // 그 판단의 주체를 남기지 않으면 배지가 아무 말도 하지 않는 장식이 된다.
    PAGE_OWNER_CHANGED,
    PAGE_VERIFIED,
    PAGE_UNVERIFIED,
    ATTACHMENT_DELETED,
    SPACE_UPDATED,
    SPACE_DELETED,
    TEMPLATE_CREATED,
    TEMPLATE_UPDATED,
    TEMPLATE_DELETED
}
