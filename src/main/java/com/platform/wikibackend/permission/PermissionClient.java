package com.platform.wikibackend.permission;

/** org-service 권한 연동 창구 — 테스트는 페이크로 대체. */
public interface PermissionClient {
    boolean isAllowed(long userId, long spaceId, WikiAction action);
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
