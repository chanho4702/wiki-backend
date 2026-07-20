package com.platform.wikibackend.permission;

/** org-service 권한 연동 창구 — 테스트는 페이크로 대체. */
public interface PermissionClient {
    boolean isAllowed(long userId, long spaceId, WikiAction action);
    AccessScope accessibleSpaces(long userId);
    /** 스페이스 생성자 자동 ADMIN 부여. 이미 있으면 false(멱등). 실패 시 예외 아님 — false. */
    boolean grantSpaceAdmin(long userId, long spaceId);
}
