package com.platform.wikibackend.permission;

import com.platform.wikibackend.permission.dto.InheritedRestriction;

import java.util.List;

/**
 * 이동 영향 확인(W18 설계 §5) — 새 위치의 조상 VIEW 제한이 이 페이지(서브트리 포함)에
 * 새로 적용될 때, 확인 없는 이동을 409로 멈추고 영향(새로 적용되는 제한)을 실어 보낸다.
 * 프론트는 확인을 받은 뒤 confirmImpact=true로 재호출한다.
 */
public class MoveImpactException extends RuntimeException {

    private final List<InheritedRestriction> newlyRestrictedBy;

    public MoveImpactException(List<InheritedRestriction> newlyRestrictedBy) {
        super("이동하면 새 위치의 보기 제한이 적용되어 일부 사용자가 접근을 잃습니다. 확인 후 다시 시도하세요");
        this.newlyRestrictedBy = newlyRestrictedBy;
    }

    public List<InheritedRestriction> getNewlyRestrictedBy() {
        return newlyRestrictedBy;
    }
}
