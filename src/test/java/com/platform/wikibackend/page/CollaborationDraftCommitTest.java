package com.platform.wikibackend.page;

import com.platform.common.error.ConflictException;
import com.platform.wikibackend.domain.CollaborationDraftMetadata;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.event.RecordingEventPublisher;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.page.dto.CollaborationDraftCommitRequest;
import com.platform.wikibackend.repository.CollaborationDraftMetadataRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.platform.wikibackend.TestPages;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class CollaborationDraftCommitTest {

    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired CollaborationDraftMetadataRepository drafts;
    @Autowired FakePermissionClient permissions;
    @Autowired RecordingEventPublisher events;

    MockMvc mvc;
    Page page;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        drafts.deleteAll();
        revisions.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        permissions.reset();
        events.reset();

        Space space = spaces.save(Space.of("collab", "공동 편집", null, 1L));
        permissions.allow(1L, space.getId(), WikiAction.EDIT);
        page = pages.save(Page.of(space.getId(), null, "기존 제목", "기존 본문", 1L));
        revisions.save(PageRevision.snapshotOf(page));
        drafts.save(CollaborationDraftMetadata.of(page.getId(), 1, 1));
    }

    @Test
    void pageRevision과_draftGeneration을_한_transaction에서_전진시킨다() throws Exception {
        mvc.perform(put("/api/wiki/pages/" + page.getId() + "/collaboration-draft")
                        .with(asUser(1L, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"공유 제목","content":"공유 본문",
                                 "expectedPageVersion":1,"expectedGeneration":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.version").value(2))
                .andExpect(jsonPath("$.page.content").value("공유 본문"))
                .andExpect(jsonPath("$.generation").value(2));

        CollaborationDraftMetadata metadata = drafts.findById("page:" + page.getId()).orElseThrow();
        assertThat(metadata.getBasePageVersion()).isEqualTo(2);
        assertThat(metadata.getGeneration()).isEqualTo(2);
        assertThat(revisions.findByPageIdAndVersion(page.getId(), 2)).isPresent();
        assertThat(events.events).anyMatch(event -> event.hasPageUpdated());
    }

    @Test
    void 같은_generation의_늦은_재요청은_409이고_revision을_추가하지_않는다() throws Exception {
        String body = """
                {"title":"공유 제목","content":"공유 본문",
                 "expectedPageVersion":1,"expectedGeneration":1}
                """;
        mvc.perform(put("/api/wiki/pages/" + page.getId() + "/collaboration-draft")
                        .with(asUser(1L, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(put("/api/wiki/pages/" + page.getId() + "/collaboration-draft")
                        .with(asUser(1L, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        assertThat(pages.findById(page.getId()).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(revisions.findByPageIdOrderByVersionDesc(page.getId())).hasSize(2);
    }

    @Test
    void 같은_generation의_두_동시_commit은_rowLock으로_하나만_성공한다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = List.of("Alice", "Bob").stream()
                    .map(title -> executor.submit(() -> {
                        start.await();
                        try {
                            pagesService().commitCollaborationDraft(1L, page.getId(),
                                    new CollaborationDraftCommitRequest(title, title + " 본문", 1, 1L));
                            return true;
                        } catch (ConflictException conflict) {
                            return false;
                        }
                    }))
                    .toList();
            start.countDown();

            assertThat(futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).filter(Boolean::booleanValue)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(pages.findById(page.getId()).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(drafts.findById("page:" + page.getId()).orElseThrow().getGeneration()).isEqualTo(2);
        assertThat(revisions.findByPageIdOrderByVersionDesc(page.getId())).hasSize(2);
    }

    @Test
    void 공유_초안이_없으면_일반_page를_덮어쓰지_않는다() throws Exception {
        drafts.deleteAll();
        mvc.perform(put("/api/wiki/pages/" + page.getId() + "/collaboration-draft")
                        .with(asUser(1L, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"공유 제목","content":"공유 본문",
                                 "expectedPageVersion":1,"expectedGeneration":1}
                                """))
                .andExpect(status().isConflict());

        assertThat(pages.findById(page.getId()).orElseThrow().getContent()).isEqualTo("기존 본문");
    }

    private PageService pagesService() {
        return context.getBean(PageService.class);
    }
}
