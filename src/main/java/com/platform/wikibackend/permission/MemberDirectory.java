package com.platform.wikibackend.permission;

import java.util.Collection;
import java.util.Map;

/**
 * 사용자 디렉터리 조회 — org-service가 사람의 원장이다. 위키는 id만 저장하고 이름·이메일·상태는 여기서
 * 읽는다(common-proto 0.16.0 {@code GetMembers}).
 *
 * <p>여러 명이면 한 번에 묻는다 — 왕복이 사람 수만큼 늘어나지 않게 한다. 없는 id는 결과에서 빠지므로
 * 호출측은 개수를 요청과 맞추지 않는다.
 *
 * <p>실패를 어떻게 다룰지는 <b>호출측이 정한다</b>. 계정 상태 게이트는 "못 읽었다"와 "그런 사람 없다"를
 * 구분해야 한다 — 전자를 후자로 읽으면 정지된 계정이 org 장애 중에 그대로 들어온다. 그래서
 * {@link #lookup}은 결과와 함께 왜 비었는지를 준다.
 *
 * <p>{@code TeamDirectory}·{@code PrincipalDirectory}와 같은 자리(이 패키지)에 둔다 — 위키의 org 조회
 * 창구는 전부 여기 모여 있다. alm-backend는 같은 것을 {@code directory} 패키지에 둔다(계약은 동일).
 */
public interface MemberDirectory {

    /** 조회가 어떻게 끝났는가 — {@code UNAVAILABLE}만 가용성 장애이고, {@code FAILED}는 그 밖의 오류다 */
    enum Outcome { OK, UNAVAILABLE, FAILED }

    record Lookup(Outcome outcome, Map<Long, DirectoryMember> members) {

        public static Lookup ok(Map<Long, DirectoryMember> members) {
            return new Lookup(Outcome.OK, members);
        }

        public boolean ok() {
            return outcome == Outcome.OK;
        }
    }

    /** id → 사람 + 조회 결과. 못 찾았거나 조회가 실패한 id는 {@code members}에 없다. */
    Lookup lookup(Collection<Long> ids);
}
