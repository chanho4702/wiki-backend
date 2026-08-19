package com.platform.wikibackend.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.migration.model.MigrationItem;
import com.platform.wikibackend.migration.model.MigrationItemStatus;
import com.platform.wikibackend.migration.model.MigrationJob;
import com.platform.wikibackend.migration.model.MigrationJobMode;
import com.platform.wikibackend.migration.model.MigrationProvider;
import com.platform.wikibackend.migration.repository.MigrationIssueRepository;
import com.platform.wikibackend.migration.repository.MigrationItemRepository;
import com.platform.wikibackend.migration.repository.MigrationJobRepository;
import com.platform.wikibackend.migration.repository.MigrationObjectMappingRepository;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class MigrationControllerTest {

    private static final long ADMIN = 7L;
    private static final long OUTSIDER = 8L;
    private static final String CHECKSUM = "c".repeat(64);

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired FakePermissionClient permissions;
    @Autowired MigrationIssueRepository issues;
    @Autowired MigrationObjectMappingRepository mappings;
    @Autowired MigrationItemRepository items;
    @Autowired MigrationJobRepository jobs;
    @Autowired SpaceRepository spaces;

    private Long spaceId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        issues.deleteAllInBatch();
        mappings.deleteAllInBatch();
        items.deleteAllInBatch();
        jobs.deleteAllInBatch();
        spaces.deleteAllInBatch();
        spaceId = spaces.save(Space.of("api" + (System.nanoTime() % 100000), "Migration", null, ADMIN)).getId();
        permissions.allow(ADMIN, spaceId, WikiAction.ADMIN);
        permissions.allow(OUTSIDER, spaceId, WikiAction.EDIT);
    }

    @Test
    void 관리자는_job을_만들고_원본을_등록한_뒤_시작할_수_있다() throws Exception {
        long jobId = createJob(MigrationJobMode.DRY_RUN);

        mvc.perform(post("/api/wiki/migrations/{id}/items", jobId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(enqueueBody("page-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalObjectId").value("page-1"))
                .andExpect(jsonPath("$.stage").value("EXTRACT"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 같은 원본을 다시 넣어도 item은 하나다 — extractor 재개가 job을 부풀리면 안 된다.
        mvc.perform(post("/api/wiki/migrations/{id}/items", jobId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(enqueueBody("page-1"))))
                .andExpect(status().isCreated());
        assertThat(items.countByJobId(jobId)).isEqualTo(1);

        mvc.perform(post("/api/wiki/migrations/{id}/start", jobId).with(asUser(ADMIN, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.itemCount").value(1));

        mvc.perform(post("/api/wiki/migrations/{id}/items", jobId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(enqueueBody("page-2"))))
                .andExpect(status().isConflict());
    }

    @Test
    void ADMIN이_아니면_job을_만들지도_읽지도_못한다() throws Exception {
        long jobId = createJob(MigrationJobMode.IMPORT);

        mvc.perform(post("/api/wiki/migrations").with(asUser(OUTSIDER, "Outsider"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(createBody(MigrationJobMode.IMPORT))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/wiki/migrations/{id}/report", jobId).with(asUser(OUTSIDER, "Outsider")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 보고서는_상태_단계_손실_dead_letter를_함께_낸다() throws Exception {
        MigrationJob job = jobs.save(MigrationJob.create(MigrationProvider.NOTION, "workspace-acme",
                spaceId, ADMIN, MigrationJobMode.DRY_RUN));
        MigrationItem dead = items.save(MigrationItem.pending(job.getId(), "page-9", "1", CHECKSUM,
                "imports/page-9.json"));
        job.start(java.time.Instant.parse("2026-08-18T09:00:00Z"));
        jobs.saveAndFlush(job);
        items.claim(dead.getId(), "worker-a", "token-1", java.time.Instant.parse("2026-08-18T09:05:00Z"),
                java.time.Instant.parse("2026-08-18T09:00:01Z"), MigrationItemStatus.RUNNING,
                MigrationItemStatus.PENDING, MigrationItemStatus.RETRY_WAIT);
        MigrationItem running = items.findById(dead.getId()).orElseThrow();
        running.deadLetter("NOTION_FORBIDDEN", java.time.Instant.parse("2026-08-18T09:00:02Z"));
        items.saveAndFlush(running);
        issues.save(com.platform.wikibackend.migration.model.MigrationIssue.of(job.getId(), running.getId(),
                com.platform.wikibackend.migration.model.MigrationIssueSeverity.ERROR,
                "NOTION_FORBIDDEN", "page-9"));

        mvc.perform(get("/api/wiki/migrations/{id}/report", job.getId()).with(asUser(ADMIN, "Admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.mode").value("DRY_RUN"))
                .andExpect(jsonPath("$.job.itemCount").value(1))
                .andExpect(jsonPath("$.itemsByStatus.DEAD_LETTER").value(1))
                .andExpect(jsonPath("$.itemsByStage.EXTRACT").value(1))
                .andExpect(jsonPath("$.issues[0].code").value("NOTION_FORBIDDEN"))
                .andExpect(jsonPath("$.issues[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.issues[0].occurrences").value(1))
                .andExpect(jsonPath("$.deadLetters[0].externalObjectId").value("page-9"))
                .andExpect(jsonPath("$.deadLetters[0].lastErrorCode").value("NOTION_FORBIDDEN"));
    }

    @Test
    void 없는_스페이스로는_job을_만들_수_없다() throws Exception {
        Map<String, Object> body = Map.of(
                "provider", "NOTION",
                "sourceInstanceId", "workspace-acme",
                "targetSpaceId", spaceId + 9999,
                "mode", "IMPORT");

        mvc.perform(post("/api/wiki/migrations").with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void checksum_형식이_틀리면_400이다() throws Exception {
        long jobId = createJob(MigrationJobMode.IMPORT);
        Map<String, Object> body = Map.of(
                "externalObjectId", "page-1",
                "sourceVersion", "1",
                "sourceChecksum", "NOT-A-CHECKSUM",
                "payloadRef", "imports/page-1.json");

        mvc.perform(post("/api/wiki/migrations/{id}/items", jobId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    private long createJob(MigrationJobMode mode) throws Exception {
        String response = mvc.perform(post("/api/wiki/migrations").with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(createBody(mode))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private Map<String, Object> createBody(MigrationJobMode mode) {
        return Map.of(
                "provider", "NOTION",
                "sourceInstanceId", "workspace-acme",
                "targetSpaceId", spaceId,
                "mode", mode.name());
    }

    private Map<String, Object> enqueueBody(String externalObjectId) {
        return Map.of(
                "externalObjectId", externalObjectId,
                "sourceVersion", "1",
                "sourceChecksum", CHECKSUM,
                "payloadRef", "imports/" + externalObjectId + ".json");
    }
}
