package com.platform.wikibackend.directory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 화면에 쓸 사람 이름을 org 원장에서 채운다 — {@code 사용자 #7}이 남아 있던 자리를 위한 것이다.
 *
 * <p>위키는 이름을 <b>쓴 시점의 스냅샷</b>으로 저장한다(리비전 `edited_by_name`, 댓글 `author_name`).
 * 그 값이 있으면 그대로 둔다: "그때 그 사람이 이 이름이었다"는 기록이고, 지금 이름으로 덮으면 옛 문서의
 * 서명이 조용히 바뀐다. 채워 넣는 것은 <b>스냅샷이 비어 있는 자리</b>뿐이다 — V28 이전 리비전, 토큰에
 * {@code name} 클레임이 없던 요청으로 만들어진 댓글, 그리고 협업 presence처럼 저장하지 않는 값.
 *
 * <p>조회는 <b>응답 하나에 한 번</b>이다. 목록을 그리면서 사람마다 물으면 화면 한 장에 org 왕복이
 * 수십 번 생긴다. 실패하면 빈 결과이고 호출부는 쓰던 폴백({@link #fallback})을 그대로 쓴다 — 이름
 * 하나 때문에 문서 조회가 503이 되지는 않는다.
 */
@Component
@Slf4j
public class DisplayNames {

    /**
     * 공개 문서 인스턴스(docs 프로필)에는 org 채널이 없어 이 빈이 존재하지 않는다 — 그래서 null을
     * 허용한다. 거기서는 언제나 폴백이고, 그것이 맞는 동작이다(그 인스턴스에 사람 원장은 없다).
     */
    @Nullable
    private final MemberDirectory directory;

    public DisplayNames(@Nullable MemberDirectory directory) {
        this.directory = directory;
    }

    /** 원장을 부르지 않는 인스턴스 — 단위 테스트와 디렉터리 없는 구성용 */
    public static DisplayNames none() {
        return new DisplayNames(null);
    }

    /** id → org 표시명. 못 읽었거나 org가 이름을 비워 둔 id는 결과에 <b>없다</b>. */
    public Map<Long, String> resolve(Collection<Long> ids) {
        if (directory == null || ids == null || ids.isEmpty()) return Map.of();
        try {
            Map<Long, String> names = new LinkedHashMap<>();
            for (Map.Entry<Long, DirectoryMember> entry : directory.members(ids).entrySet()) {
                if (entry.getValue().hasDisplayName()) names.put(entry.getKey(), entry.getValue().displayName());
            }
            return names;
        } catch (Exception e) {
            // 이름은 있으면 좋은 값이다 — 못 읽었다고 응답을 막지 않는다
            log.warn("표시명을 읽지 못해 폴백을 쓴다: users={}", ids, e);
            return Map.of();
        }
    }

    public Optional<String> resolve(long id) {
        return Optional.ofNullable(resolve(List.of(id)).get(id));
    }

    /** org도 모르는 사람에게 남는 이름. 이 문자열이 화면에 보이면 원장에 그 id가 없다는 뜻이다. */
    public static String fallback(long userId) {
        return "사용자 #" + userId;
    }

    /** 채워 넣어야 할 자리인가 — 비었거나 그 자리를 메우려고 넣어 둔 폴백 문자열이면 그렇다 */
    public static boolean needsFill(String stored, long userId) {
        return stored == null || stored.isBlank() || stored.equals(fallback(userId));
    }
}
