package com.platform.wikibackend.config;

import com.platform.wikibackend.permission.AccessScope;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.permission.WikiAction;

import java.util.Set;

/**
 * 공개 문서 인스턴스(docs)의 권한 판정 — org-service를 호출하지 않는다.
 *
 * 이 인스턴스의 DB에는 공개해도 되는 문서만 들어 있다(임포터만 쓴다). 그래서 VIEW는 누구에게나
 * 열고, 그보다 위(COMMENT·EDIT·ADMIN)는 임포터 주체에게만 준다. 임포터가 아닌 요청은 애초에
 * 필터 체인에서 막히지만, 권한 계층에서도 한 번 더 닫아 둔다 — 새 쓰기 엔드포인트가 생겨
 * 경로 규칙에서 빠지더라도 조용히 열리지 않게 하기 위해서다.
 */
public class PublicReadPermissionClient implements PermissionClient {

    @Override
    public boolean isAllowed(long userId, long spaceId, WikiAction action) {
        if (action == WikiAction.VIEW) return true;
        return userId == DocsPrincipalFilter.IMPORTER_USER_ID;
    }

    /** 스페이스가 전부 공개다 — 목록·검색이 스페이스 화이트리스트로 걸리지 않게 all=true. */
    @Override
    public AccessScope accessibleSpaces(long userId) {
        return new AccessScope(true, Set.of());
    }

    /** 임포터가 스페이스를 만들 때 호출된다. 원장이 없으므로 부여할 것도 없다 — 성공으로 본다. */
    @Override
    public boolean grantSpaceAdmin(long userId, long spaceId) {
        return true;
    }

    /** 회수할 grant가 존재하지 않는다. */
    @Override
    public int revokeSpaceGrants(long spaceId) {
        return 0;
    }
}
