package com.platform.wikibackend.notification;

import com.platform.wikibackend.notification.dto.NotificationListResponse;
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

    @GetMapping("/api/wiki/notifications")
    public NotificationListResponse list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt));
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
