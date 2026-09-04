package com.platform.wikibackend.migration;

import com.platform.wikibackend.migration.dto.ConfluenceDcProbeRequest;
import com.platform.wikibackend.migration.dto.ConfluenceDcProbeResponse;
import com.platform.wikibackend.migration.dto.MigrationDiscoverResponse;
import com.platform.wikibackend.migration.dto.MigrationItemEnqueueRequest;
import com.platform.wikibackend.migration.dto.MigrationItemPageResponse;
import com.platform.wikibackend.migration.dto.MigrationItemResponse;
import com.platform.wikibackend.migration.dto.MigrationJobCreateRequest;
import com.platform.wikibackend.migration.dto.MigrationJobDetailResponse;
import com.platform.wikibackend.migration.dto.MigrationJobSummary;
import com.platform.wikibackend.migration.model.MigrationItemStatus;
import com.platform.wikibackend.migration.model.MigrationStage;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

@RestController
@RequestMapping("/api/wiki/migrations")
@RequiredArgsConstructor
public class MigrationController {

    private final MigrationJobService migrations;

    /** 연결 확인 — 토큰은 요청 본문으로만 들어오고 응답에는 실리지 않는다. */
    @PostMapping("/confluence-dc/probe")
    public ConfluenceDcProbeResponse probeConfluenceDc(@AuthenticationPrincipal Jwt jwt,
                                                       @Valid @RequestBody ConfluenceDcProbeRequest req) {
        return migrations.probeConfluenceDc(userId(jwt), req);
    }

    /** 관리자용 잡 목록(최신순). */
    @GetMapping
    public List<MigrationJobSummary> list(@AuthenticationPrincipal Jwt jwt) {
        return migrations.list(userId(jwt));
    }

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

    /** 원본 트리를 훑어 대기열을 채운다. 다시 눌러도 새 항목만 늘어난다(멱등). */
    @PostMapping("/{jobId}/discover")
    public MigrationDiscoverResponse discover(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId) {
        return migrations.discover(userId(jwt), jobId, Instant.now());
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
    public MigrationJobDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId) {
        return migrations.detail(userId(jwt), jobId);
    }

    /** 실패 항목 표. status·stage는 선택 필터, page는 0부터. */
    @GetMapping("/{jobId}/items")
    public MigrationItemPageResponse items(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId,
                                           @RequestParam(required = false) MigrationItemStatus status,
                                           @RequestParam(required = false) MigrationStage stage,
                                           @RequestParam(defaultValue = "0") int page) {
        return migrations.listItems(userId(jwt), jobId, status, stage, page);
    }

    @GetMapping("/{jobId}/report")
    public MigrationReportResponse report(@AuthenticationPrincipal Jwt jwt, @PathVariable long jobId) {
        return migrations.report(userId(jwt), jobId);
    }
}
