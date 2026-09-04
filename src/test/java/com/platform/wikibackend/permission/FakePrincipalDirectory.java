package com.platform.wikibackend.permission;

import com.platform.wikibackend.permission.dto.RestrictionPrincipal;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** 테스트 기본은 양수 ID를 실재로 보고, missing으로 지정한 주체만 거절한다. */
@Component
@Primary
@org.springframework.context.annotation.Profile("!docs")   // docs 프로필은 프로필 전용 빈을 그대로 검증한다
public class FakePrincipalDirectory implements PrincipalDirectory {

    private final Set<RestrictionPrincipal> missing = new HashSet<>();

    public void reset() {
        missing.clear();
    }

    public void markMissing(RestrictionPrincipal principal) {
        missing.add(principal);
    }

    @Override
    public void requireExisting(Collection<RestrictionPrincipal> principals) {
        for (RestrictionPrincipal principal : principals) {
            principal.toType();
            if (principal.id() <= 0 || missing.contains(principal)) {
                throw new IllegalArgumentException("존재하지 않는 제한 주체입니다: "
                        + principal.type() + " " + principal.id());
            }
        }
    }
}
