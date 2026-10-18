package com.platform.wikibackend.notification;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.permission.EffectivePermissionService;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 알림이 가리키는 문서를 "지금" 볼 수 있는지 — 알림함과 요약 메일이 같은 판정을 쓴다.
 *
 * 알림 행은 만들어진 시점의 권한으로 걸러졌을 뿐이다. 그 뒤 문서에 VIEW 제한이 걸리거나 스페이스
 * 권한이 회수되면, 제목과 경로를 보여 주는 모든 출구(알림함, 요약 메일)가 다시 판정해야 한다.
 * 출구마다 판정 블록을 따로 두면 한쪽이 빠진다(요약 메일이 그랬다, 감사 W-04).
 *
 * space 권한은 스페이스당 한 번, 페이지 제한은 묶음 한 번이다. org가 불능이면
 * {@link com.platform.common.error.ServiceUnavailableException}이 그대로 올라간다 — 호출부가
 * 503으로 낼지(알림함) 그 사용자를 건너뛸지(요약) 정한다.
 */
@Component
@RequiredArgsConstructor
public class NotificationVisibility {

    private final PermissionClient permissions;
    private final EffectivePermissionService effective;

    public Set<Long> visiblePageIds(long userId, Collection<Page> pages) {
        if (pages.isEmpty()) return Set.of();
        Map<Long, Boolean> spaceVisible = new HashMap<>();
        for (Page page : pages) {
            spaceVisible.computeIfAbsent(page.getSpaceId(),
                    spaceId -> permissions.isAllowed(userId, spaceId, WikiAction.VIEW));
        }
        Set<Long> effectiveVisible = effective.viewablePageIds(userId, pages);
        Set<Long> out = new HashSet<>();
        for (Page page : pages) {
            if (spaceVisible.getOrDefault(page.getSpaceId(), false) && effectiveVisible.contains(page.getId())) {
                out.add(page.getId());
            }
        }
        return out;
    }
}
