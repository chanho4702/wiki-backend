package com.platform.wikibackend.permission;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 테스트 전용 사용자 디렉터리 페이크 — test 소스셋의 컴포넌트 스캔으로 등록된다.
 *
 * <p><b>기본값이 "빈 디렉터리"인 것이 중요하다.</b> 그러면 계정 상태 게이트가 "org에 아직 없는 사람"으로
 * 보고 통과시키므로, 상태를 다루지 않는 기존 테스트들은 게이트가 생겨도 그대로 돈다. 상태를 검증하는
 * 테스트만 {@link #put}으로 사람을 넣는다.
 */
@Component
@org.springframework.context.annotation.Primary
@org.springframework.context.annotation.Profile("!docs")
public class FakeMemberDirectory implements MemberDirectory {

    private final Map<Long, DirectoryMember> members = new LinkedHashMap<>();
    private final List<List<Long>> calls = new CopyOnWriteArrayList<>();

    private volatile boolean unavailable = false;
    private volatile boolean failed = false;

    public void put(long id, String name, String email, String status) {
        members.put(id, new DirectoryMember(id, name, email, status, "HUMAN"));
    }

    public void setUnavailable(boolean value) { this.unavailable = value; }

    public void setFailed(boolean value) { this.failed = value; }

    /** 조회 호출 기록 — 캐시가 실제로 왕복을 줄이는지 본다 */
    public List<List<Long>> calls() { return calls; }

    public void reset() {
        members.clear();
        calls.clear();
        unavailable = false;
        failed = false;
    }

    @Override
    public Lookup lookup(Collection<Long> ids) {
        calls.add(List.copyOf(ids));
        if (unavailable) return new Lookup(Outcome.UNAVAILABLE, Map.of());
        if (failed) return new Lookup(Outcome.FAILED, Map.of());
        Map<Long, DirectoryMember> found = new LinkedHashMap<>();
        for (Long id : new ArrayList<>(ids)) {
            DirectoryMember member = members.get(id);
            if (member != null) found.put(id, member);
        }
        return Lookup.ok(found);
    }
}
