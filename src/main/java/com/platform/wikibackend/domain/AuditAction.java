package com.platform.wikibackend.domain;

/**
 * 기록하는 조작.
 *
 * **되돌리기 어렵거나 접근 범위를 바꾸는 것만** 넣는다. 본문 수정은 리비전이 이미 남기고,
 * 모든 조회까지 기록하면 감사 로그가 아니라 활동 추적이 되어 보존 정책 논의가 따라붙는다.
 *
 * SPACE_DELETED는 스페이스 스코프 화면에서는 볼 수 없다(스페이스가 없다) — 전역 관리자의
 * "스페이스 삭제 기록" 목록이 읽는다. V30에서 space FK를 풀어 기록이 스페이스보다 오래 남는다.
 *
 * 각 값은 한국어 라벨을 들고 있다. 전역 감사 피드(관리자 대시보드 "최근 활동")가 한 줄
 * 요약으로 쓴다 — 프론트가 enum 이름을 다시 한국어로 매핑하면 값이 늘어날 때마다 두 곳이
 * 어긋난다. 문구의 정본은 여기 하나다.
 */
public enum AuditAction {
    PAGE_TRASHED("페이지 휴지통 이동"),
    PAGE_RESTORED("페이지 복원"),
    PAGE_PURGED("페이지 완전 삭제"),
    PAGE_ARCHIVED("페이지 보관"),
    PAGE_UNARCHIVED("페이지 보관 해제"),
    PAGE_RESTRICTIONS_CHANGED("페이지 제한 변경"),
    // 소유자·검증(W27-5)은 되돌리기 어렵지는 않지만 "누가 이 문서를 맞다고 했나"의 근거다 —
    // 그 판단의 주체를 남기지 않으면 배지가 아무 말도 하지 않는 장식이 된다.
    PAGE_OWNER_CHANGED("페이지 소유자 변경"),
    PAGE_VERIFIED("페이지 검증"),
    PAGE_UNVERIFIED("페이지 검증 해제"),
    ATTACHMENT_DELETED("첨부 삭제"),
    SPACE_UPDATED("스페이스 정보 변경"),
    SPACE_DELETED("스페이스 삭제"),
    TEMPLATE_CREATED("템플릿 생성"),
    TEMPLATE_UPDATED("템플릿 수정"),
    TEMPLATE_DELETED("템플릿 삭제"),
    /**
     * 외부 위키에서 옮겨온 문서(W29 X1). 이관 엔진이 부르는 내부 import API가 **문서 한 건당
     * 한 번만** 남긴다 — 뒤따르는 첨부·댓글·제한·본문 정리까지 기록하면 한 번의 이관이 감사
     * 로그를 수백 줄로 덮어 정작 봐야 할 삭제·권한 변경이 목록 밖으로 밀려난다.
     */
    IMPORTED("외부 위키에서 이관");

    private final String label;

    AuditAction(String label) {
        this.label = label;
    }

    /** 사람이 읽는 한 줄 요약. */
    public String label() {
        return label;
    }

    /**
     * 저장된 문자열(`audit_log.action`)에 붙는 라벨. 모르는 값이면 문자열 그대로 —
     * 옛 기록이나 지워진 enum 값 때문에 피드 전체가 깨지지 않게 한다.
     */
    public static String labelOf(String action) {
        for (AuditAction value : values()) {
            if (value.name().equals(action)) {
                return value.label;
            }
        }
        return action;
    }
}
