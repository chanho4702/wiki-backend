package com.platform.wikibackend.permission;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 테스트 전용 페이크 — test 소스셋의 컴포넌트 스캔으로 등록된다.
 * @Primary: SecurityConfig의 @ConditionalOnMissingBean은 빈 등록 순서에 따라 gRPC 빈이
 * 함께 생길 수 있으므로(알려진 fragility), 주입 우선권으로 확실히 대체한다.
 */
@Component
@org.springframework.context.annotation.Primary
public class FakePermissionClient implements PermissionClient {

    private record Key(long userId, long spaceId, WikiAction action) {}

    private final Set<Key> allowed = new HashSet<>();
    private final Set<Long> allowAllUsers = new HashSet<>();
    public final List<long[]> grantedAdmins = new ArrayList<>(); // [userId, spaceId] 기록
    public final List<Long> revokedSpaces = new ArrayList<>();   // 회수 호출된 spaceId 기록

    public void allow(long userId, long spaceId, WikiAction action) { allowed.add(new Key(userId, spaceId, action)); }
    public void allowAll(long userId) { allowAllUsers.add(userId); }
    public void reset() { allowed.clear(); allowAllUsers.clear(); grantedAdmins.clear(); revokedSpaces.clear(); }

    @Override
    public boolean isAllowed(long userId, long spaceId, WikiAction action) {
        if (allowAllUsers.contains(userId) || allowed.contains(new Key(userId, spaceId, action))) return true;
        // org-service의 계층(EDITOR·ADMIN ⊃ COMMENTER)을 흉내 낸다 — 편집자에게 COMMENT를 따로 주지 않아도 된다
        return action == WikiAction.COMMENT
                && (allowed.contains(new Key(userId, spaceId, WikiAction.EDIT))
                    || allowed.contains(new Key(userId, spaceId, WikiAction.ADMIN)));
    }

    @Override
    public AccessScope accessibleSpaces(long userId) {
        if (allowAllUsers.contains(userId)) return new AccessScope(true, Set.of());
        Set<Long> ids = new HashSet<>();
        for (Key k : allowed) if (k.userId() == userId && k.action() == WikiAction.VIEW) ids.add(k.spaceId());
        return new AccessScope(false, ids);
    }

    @Override
    public boolean grantSpaceAdmin(long userId, long spaceId) {
        grantedAdmins.add(new long[]{userId, spaceId});
        allow(userId, spaceId, WikiAction.VIEW);
        allow(userId, spaceId, WikiAction.EDIT);
        allow(userId, spaceId, WikiAction.ADMIN);
        return true;
    }

    @Override
    public int revokeSpaceGrants(long spaceId) {
        revokedSpaces.add(spaceId);
        int revoked = 0;
        for (Key k : Set.copyOf(allowed)) {
            if (k.spaceId() == spaceId && allowed.remove(k)) revoked++;
        }
        return revoked;
    }
}
