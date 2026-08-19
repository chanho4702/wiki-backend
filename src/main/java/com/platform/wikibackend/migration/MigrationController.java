package com.platform.wikibackend.migration;

import com.platform.wikibackend.migration.dto.MigrationItemEnqueueRequest;
import com.platform.wikibackend.migration.dto.MigrationItemResponse;
import com.platform.wikibackend.migration.dto.MigrationJobCreateRequest;
import com.platform.wikibackend.migration.report.MigrationJobResponse;
import com.platform.wikibackend.migration.report.MigrationReportResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static com.platform.wikibackend.space.SpaceController.userId;

@RestController
@RequestMapping("/api/wiki/migrations")
@RequiredArgsConstructor
public class MigrationController {

    private final MigrationJobService migrations;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MigrationJobResponse create(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody MigrationJobCreateRequest req) {
        return migrations.create(userId(jwt), req);
    }

    @PostMapping("/{jobId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public MigrationItemResponse enqueue(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId,
                                         @Valid @RequestBody MigrationItemEnqueueRequest req) {
        return migrations.enqueue(userId(jwt), jobId, req);
    }

    @PostMapping("/{jobId}/start")
    public MigrationJobResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId) {
        return migrations.start(userId(jwt), jobId, Instant.now());
    }

    @PostMapping("/{jobId}/cancel")
    public MigrationJobResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId) {
        return migrations.cancel(userId(jwt), jobId, Instant.now());
    }

    @GetMapping("/{jobId}")
    public MigrationJobResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId) {
        return migrations.get(userId(jwt), jobId);
    }

    @GetMapping("/{jobId}/report")
    public MigrationReportResponse report(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId) {
        return migrations.report(userId(jwt), jobId);
    }
}
