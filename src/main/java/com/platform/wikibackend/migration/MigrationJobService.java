package com.platform.wikibackend.migration;

import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.wikibackend.migration.dto.MigrationItemEnqueueRequest;
import com.platform.wikibackend.migration.dto.MigrationItemResponse;
import com.platform.wikibackend.migration.dto.MigrationJobCreateRequest;
import com.platform.wikibackend.migration.model.MigrationItem;
import com.platform.wikibackend.migration.model.MigrationItemStatus;
import com.platform.wikibackend.migration.model.MigrationJob;
import com.platform.wikibackend.migration.model.MigrationJobStatus;
import com.platform.wikibackend.migration.report.MigrationDeadLetterResponse;
import com.platform.wikibackend.migration.report.MigrationIssueSummary;
import com.platform.wikibackend.migration.report.MigrationJobResponse;
import com.platform.wikibackend.migration.report.MigrationReportResponse;
import com.platform.wikibackend.migration.report.MigrationStageCount;
import com.platform.wikibackend.migration.report.MigrationStatusCount;
import com.platform.wikibackend.migration.repository.MigrationIssueRepository;
import com.platform.wikibackend.migration.repository.MigrationItemRepository;
import com.platform.wikibackend.migration.repository.MigrationJobRepository;
import com.platform.wikibackend.permission.PermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * migration job의 수명주기와 보고서. 대상 스페이스 ADMIN만 job을 만들고 읽을 수 있다 —
 * 보고서에는 아직 옮기지 않은 외부 객체 ID가 들어가므로 VIEW 권한으로는 열지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MigrationJobService {

    private final MigrationJobRepository jobs;
    private final MigrationItemRepository items;
    private final MigrationIssueRepository issues;
    private final MigrationItemIntake intake;
    private final SpaceRepository spaces;
    private final PermissionClient permissions;

    public MigrationJobResponse create(long userId, MigrationJobCreateRequest req) {
        if (!spaces.existsById(req.targetSpaceId())) {
            throw new NotFoundException("스페이스를 찾을 수 없습니다: " + req.targetSpaceId());
        }
        requireAdmin(userId, req.targetSpaceId());
        MigrationJob job = jobs.save(MigrationJob.create(req.provider(), req.sourceInstanceId(),
                req.targetSpaceId(), userId, req.mode()));
        return MigrationJobResponse.from(job, 0);
    }

    /**
     * 원본 객체를 job에 등록한다. 같은 외부 객체를 다시 넣어도 새 item을 만들지 않는다 —
     * extractor가 중단 후 재개해도 job이 부풀지 않아야 한다.
     *
     * 트랜잭션을 열지 않는 이유: 두 재시도가 동시에 들어오면 둘 다 조회에서 못 찾고 같은
     * `(job_id, source_key)`를 넣으려 한다. 하나는 unique 제약에 걸리는데, 그 예외가 열린
     * 트랜잭션을 rollback-only로 만들면 같은 트랜잭션에서 다시 읽어 멱등 응답을 줄 수 없다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MigrationItemResponse enqueue(long userId, long jobId, MigrationItemEnqueueRequest req) {
        MigrationJob job = requireJob(userId, jobId);
        if (job.getStatus() != MigrationJobStatus.PENDING) {
            throw new ConflictException("이미 시작된 job에는 원본을 추가할 수 없습니다: " + job.getStatus());
        }
        String sourceKey = MigrationItem.sourceKeyFor(req.externalObjectId());
        Optional<MigrationItem> existing = items.findByJobIdAndSourceKey(jobId, sourceKey);
        if (existing.isPresent()) {
            return MigrationItemResponse.from(existing.get());
        }
        try {
            return MigrationItemResponse.from(intake.insert(jobId, req));
        } catch (DataIntegrityViolationException e) {
            return items.findByJobIdAndSourceKey(jobId, sourceKey)
                    .map(MigrationItemResponse::from)
                    .orElseThrow(() -> e);
        }
    }

    /** 등록을 마감하고 worker가 집을 수 있게 한다. 이후 enqueue는 409다. */
    public MigrationJobResponse start(long userId, long jobId, Instant now) {
        MigrationJob job = lockJob(userId, jobId);
        if (job.getStatus() != MigrationJobStatus.PENDING) {
            throw new ConflictException("이미 시작된 job입니다: " + job.getStatus());
        }
        job.start(now);
        return MigrationJobResponse.from(job, items.countByJobId(jobId));
    }

    public MigrationJobResponse cancel(long userId, long jobId, Instant now) {
        MigrationJob job = lockJob(userId, jobId);
        if (job.getStatus() != MigrationJobStatus.PENDING && job.getStatus() != MigrationJobStatus.RUNNING) {
            throw new ConflictException("이미 종료된 job입니다: " + job.getStatus());
        }
        job.cancel(now);
        return MigrationJobResponse.from(job, items.countByJobId(jobId));
    }

    @Transactional(readOnly = true)
    public MigrationJobResponse get(long userId, long jobId) {
        MigrationJob job = requireJob(userId, jobId);
        return MigrationJobResponse.from(job, items.countByJobId(jobId));
    }

    @Transactional(readOnly = true)
    public MigrationReportResponse report(long userId, long jobId) {
        MigrationJob job = requireJob(userId, jobId);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (MigrationStatusCount count : items.countByStatus(jobId)) {
            byStatus.put(count.status().name(), count.total());
        }
        Map<String, Long> byStage = new LinkedHashMap<>();
        for (MigrationStageCount count : items.countByStage(jobId)) {
            byStage.put(count.stage().name(), count.total());
        }
        // severity는 문자열 컬럼이라 DB 정렬이 심각도 순이 아니다. 심각한 것부터 보이게 여기서 정렬한다.
        List<MigrationIssueSummary> summaries = issues.summarize(jobId).stream()
                .sorted(Comparator.comparing((MigrationIssueSummary s) -> s.severity().ordinal()).reversed()
                        .thenComparing(MigrationIssueSummary::code))
                .toList();
        List<MigrationDeadLetterResponse> deadLetters = items
                .findByJobIdAndStatusOrderByIdAsc(jobId, MigrationItemStatus.DEAD_LETTER).stream()
                .map(MigrationDeadLetterResponse::from)
                .toList();
        return new MigrationReportResponse(MigrationJobResponse.from(job, items.countByJobId(jobId)),
                byStatus, byStage, summaries, deadLetters);
    }

    private MigrationJob requireJob(long userId, long jobId) {
        MigrationJob job = jobs.findById(jobId)
                .orElseThrow(() -> new NotFoundException("마이그레이션 작업을 찾을 수 없습니다: " + jobId));
        requireAdmin(userId, job.getTargetSpaceId());
        return job;
    }

    /**
     * 상태를 바꾸기 전에 행을 잠근다. 동시 `start`/`cancel`이 같은 상태를 읽고 둘 다 통과하면
     * 뒤늦은 쪽이 커밋 시점의 낙관적 락 실패로 500을 내는데, 사용자에게는 상태 충돌(409)이어야 한다.
     */
    private MigrationJob lockJob(long userId, long jobId) {
        MigrationJob job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new NotFoundException("마이그레이션 작업을 찾을 수 없습니다: " + jobId));
        requireAdmin(userId, job.getTargetSpaceId());
        return job;
    }

    private void requireAdmin(long userId, Long spaceId) {
        if (spaceId == null || !permissions.isAllowed(userId, spaceId, WikiAction.ADMIN)) {
            throw new ForbiddenException("마이그레이션 권한이 없습니다");
        }
    }
}
