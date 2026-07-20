package com.platform.wikibackend.space;

import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.event.RecordingEventPublisher;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
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

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class SpaceControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pageRepo;
    @Autowired PageRevisionRepository revisionRepo;
    @Autowired AttachmentRepository attachmentRepo;
    @Autowired FakePermissionClient perms;
    @Autowired RecordingEventPublisher events;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        attachmentRepo.deleteAll();
        revisionRepo.deleteAll();
        pageRepo.deleteAll();
        spaces.deleteAll();
        perms.reset();
        events.reset();
    }

    @Test
    void 무토큰은_401() throws Exception {
        mvc.perform(get("/api/wiki/spaces")).andExpect(status().isUnauthorized());
    }

    @Test
    void 생성하면_생성자에게_ADMIN이_자동_부여되고_이벤트가_발행된다() throws Exception {
        mvc.perform(post("/api/wiki/spaces").with(asUser(1L, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"dev\",\"name\":\"개발 위키\",\"description\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("dev"));

        assertThat(perms.grantedAdmins).hasSize(1);
        assertThat(events.events).hasSize(1);
        assertThat(events.events.get(0).hasSpaceCreated()).isTrue();
    }

    @Test
    void key_중복은_400() throws Exception {
        mvc.perform(post("/api/wiki/spaces").with(asUser(1L, "Alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"dev\",\"name\":\"a\",\"description\":null}")).andExpect(status().isCreated());
        mvc.perform(post("/api/wiki/spaces").with(asUser(1L, "Alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"dev\",\"name\":\"b\",\"description\":null}")).andExpect(status().isBadRequest());
    }

    @Test
    void 목록은_접근_가능한_스페이스만_반환한다() throws Exception {
        Space visible = spaces.save(Space.of("a", "보임", null, 9L));
        spaces.save(Space.of("b", "안보임", null, 9L));
        perms.allow(2L, visible.getId(), WikiAction.VIEW);

        mvc.perform(get("/api/wiki/spaces").with(asUser(2L, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value("a"));
    }

    @Test
    void 수정과_삭제는_ADMIN만_가능하다() throws Exception {
        Space s = spaces.save(Space.of("ops", "운영", null, 9L));
        perms.allow(2L, s.getId(), WikiAction.VIEW); // VIEW만

        mvc.perform(put("/api/wiki/spaces/" + s.getId()).with(asUser(2L, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"description\":null}"))
                .andExpect(status().isForbidden());

        perms.allow(2L, s.getId(), WikiAction.ADMIN);
        mvc.perform(put("/api/wiki/spaces/" + s.getId()).with(asUser(2L, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"description\":null}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/wiki/spaces/" + s.getId()).with(asUser(2L, "Bob")))
                .andExpect(status().isNoContent());
        assertThat(events.events.stream().filter(e -> e.hasSpaceDeleted())).hasSize(1);
    }

    @Test
    void 스페이스를_삭제하면_페이지_리비전_첨부_행이_함께_정리된다() throws Exception {
        Space s = spaces.save(Space.of("gone", "삭제될 곳", null, 9L));
        perms.allow(2L, s.getId(), WikiAction.ADMIN);
        Page p = pageRepo.save(Page.of(s.getId(), null, "t", "c", 9L));
        revisionRepo.save(PageRevision.snapshotOf(p));
        attachmentRepo.save(Attachment.of(p.getId(), "f.png", "image/png", 4L, "no-such-key", 9L));

        mvc.perform(delete("/api/wiki/spaces/" + s.getId()).with(asUser(2L, "Bob")))
                .andExpect(status().isNoContent());

        assertThat(pageRepo.findBySpaceIdOrderById(s.getId())).isEmpty();
        assertThat(revisionRepo.findByPageIdOrderByVersionDesc(p.getId())).isEmpty();
        assertThat(attachmentRepo.findByPageId(p.getId())).isEmpty();
    }
}
