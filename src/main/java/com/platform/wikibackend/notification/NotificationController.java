package com.platform.wikibackend.notification;

import com.platform.wikibackend.notification.dto.NotificationListResponse;
import com.platform.wikibackend.notification.dto.NotificationPrefResponse;
import com.platform.wikibackend.notification.dto.NotificationPrefUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;
    private final NotificationPrefService prefs;

    @GetMapping("/api/wiki/notifications")
    public NotificationListResponse list(@AuthenticationPrincipal Jwt jwt) {
        // 알림함을 여는 순간이 주소를 알게 되는 가장 이른 때다 — 설정을 연 적 없는 사용자도 메일을 받게.
        prefs.remember(userId(jwt), jwt.getClaimAsString("email"));
        return service.list(userId(jwt));
    }

    /** 알림 설정(W23) — 이메일 채널 스위치. */
    @GetMapping("/api/wiki/notifications/prefs")
    public NotificationPrefResponse prefs(@AuthenticationPrincipal Jwt jwt) {
        return prefs.view(userId(jwt), jwt.getClaimAsString("email"));
    }

    @PutMapping("/api/wiki/notifications/prefs")
    public NotificationPrefResponse updatePrefs(@AuthenticationPrincipal Jwt jwt,
                                                @RequestBody NotificationPrefUpdate req) {
        return prefs.update(userId(jwt), jwt.getClaimAsString("email"), req);
    }

    /** ids 비우면 전체 읽음. */
    @PostMapping("/api/wiki/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) ReadRequest req) {
        service.markRead(userId(jwt), req == null ? List.of() : req.ids());
    }

    public record ReadRequest(List<Long> ids) {
    }
}
