package com.platform.wikibackend.permission;

/** org-service 권한 연동 창구 — 테스트는 페이크로 대체. */
public interface PermissionClient {

    /**
     * 스페이스 단위 판정. 거부면 사유(common-proto 0.16.0 {@code denied_reason})까지 온다 —
     * 403 문구를 정하는 호출부는 {@code isAllowed}가 아니라 이쪽을 쓴다.
     */
    PermissionDecision check(long userId, long spaceId, WikiAction action);

    /**
     * 사유가 필요 없는 곳(목록·트리·알림 필터처럼 예외를 던지지 않고 거르기만 하는 경로)의 편의 메서드.
     * 판정 자체는 {@link #check}와 같다 — 캐시도 공유한다.
     */
    default boolean isAllowed(long userId, long spaceId, WikiAction action) {
        return check(userId, spaceId, action).allowed();
    }

    AccessScope accessibleSpaces(long userId);

    /** 스페이스 생성자 자동 ADMIN 부여. 이미 있으면 false(멱등). 실패 시 예외 아님 — false. */
    boolean grantSpaceAdmin(long userId, long spaceId);

    /**
     * 스페이스 삭제 시 그 스페이스에 걸린 grant 전부 회수. 회수한 수를 반환(대상 없으면 0).
     * 실패해도 예외가 아니다 — 스페이스는 이미 지워졌고, 남은 고아 grant 때문에 삭제를
     * 되돌릴 수는 없다. 대신 경고 로그로 남긴다.
     */
    int revokeSpaceGrants(long spaceId);
}
