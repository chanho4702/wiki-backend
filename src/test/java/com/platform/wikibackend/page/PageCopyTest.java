package com.platform.wikibackend.page;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.attachment.AttachmentLifecycleStatus;
import com.platform.wikibackend.attachment.StorageBackend;
import com.platform.wikibackend.attachment.StoredObject;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.Space;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** 페이지 복제(v1) 계약 — 단일 페이지, 첨부 객체 복사와 본문 참조 재작성, PENDING 제외. */
@SpringBootTest
@ActiveProfiles("test")
class PageCopyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long EDITOR = 1L;
    private static final long VIEWER = 2L;

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired AttachmentRepository attachments;
    @Autowired FakePermissionClient perms;
    @Autowired com.platform.wikibackend.attachment.AttachmentStorageRouter storage;
    @Autowired com.platform.wikibackend.repository.PageRestrictionRepository restrictions;

    private Long spaceId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        restrictions.deleteAllInBatch();
        attachments.deleteAllInBatch();
        revisions.deleteAllInBatch();
        pages.deleteAllInBatch();
        spaces.deleteAllInBatch();
        perms.reset();
        spaceId = spaces.save(Space.of("cp" + (System.nanoTime() % 100000), "복제", null, EDITOR)).getId();
        perms.allow(EDITOR, spaceId, WikiAction.VIEW);
        perms.allow(EDITOR, spaceId, WikiAction.EDIT);
        perms.allow(VIEWER, spaceId, WikiAction.VIEW);
    }

    @Test
    void 첨부까지_복사되고_본문_참조가_사본_첨부로_재작성된다() throws Exception {
        Page source = pages.save(Page.of(spaceId, null, "원본", "", EDITOR));
        byte[] bytes = "png-bytes".getBytes(StandardCharsets.UTF_8);
        StoredObject stored = storage.store(new ByteArrayInputStream(bytes), bytes.length, "image/png");
        Attachment original = attachments.save(Attachment.of(source.getId(), "shot.png", "image/png",
                (long) bytes.length, stored, "a".repeat(64), EDITOR));
        source.edit("원본", "![캡처](/api/wiki/attachments/" + original.getId() + "/inline#w=480)", EDITOR);
        pages.saveAndFlush(source);

        String body = mvc.perform(post("/api/wiki/pages/{id}/copy", source.getId()).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("원본 (사본)"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        long copyId = JSON.readTree(body).get("id").asLong();

        List<Attachment> copiedAttachments = attachments.findByPageId(copyId);
        assertThat(copiedAttachments).hasSize(1);
        Attachment copied = copiedAttachments.get(0);
        assertThat(copied.getId()).isNotEqualTo(original.getId());
        assertThat(copied.getFilename()).isEqualTo("shot.png");
        // 객체도 별도 저장이다 — 원본 삭제가 사본을 깨뜨리면 안 된다
        assertThat(copied.getStorageKey()).isNotEqualTo(original.getStorageKey());

        Page copy = pages.findById(copyId).orElseThrow();
        assertThat(copy.getContent())
                .contains("/api/wiki/attachments/" + copied.getId() + "/inline#w=480")
                .doesNotContain("/api/wiki/attachments/" + original.getId() + "/inline");
        // 리비전 1도 재작성된 본문으로 남는다
        assertThat(revisions.findByPageIdOrderByVersionDesc(copyId).get(0).getContent())
                .isEqualTo(copy.getContent());
    }

    @Test
    void PENDING_첨부와_하위_페이지는_기본으로_복사되지_않는다() throws Exception {
        Page source = pages.save(Page.of(spaceId, null, "원본", "본문", EDITOR));
        pages.save(Page.of(spaceId, source.getId(), "하위", "", EDITOR));
        byte[] bytes = "tmp".getBytes(StandardCharsets.UTF_8);
        StoredObject stored = storage.store(new ByteArrayInputStream(bytes), bytes.length, "image/png");
        attachments.save(Attachment.of(source.getId(), "tmp.png", "image/png", (long) bytes.length,
                stored, "b".repeat(64), EDITOR, AttachmentLifecycleStatus.PENDING));

        String body = mvc.perform(post("/api/wiki/pages/{id}/copy", source.getId()).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long copyId = JSON.readTree(body).get("id").asLong();

        assertThat(attachments.findByPageId(copyId)).isEmpty();
        assertThat(pages.findByParentId(copyId)).isEmpty();
        Page copy = pages.findById(copyId).orElseThrow();
        assertThat(copy.getParentId()).isEqualTo(source.getParentId());
        assertThat(copy.getContent()).isEqualTo("본문");
    }

    @Test
    void EDIT_권한이_없으면_복제할_수_없다() throws Exception {
        Page source = pages.save(Page.of(spaceId, null, "원본", "", EDITOR));
        mvc.perform(post("/api/wiki/pages/{id}/copy", source.getId()).with(asUser(VIEWER, "Bob")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 긴_제목도_255자를_넘지_않게_잘라_붙인다() throws Exception {
        Page source = pages.save(Page.of(spaceId, null, "가".repeat(255), "", EDITOR));
        mvc.perform(post("/api/wiki/pages/{id}/copy", source.getId()).with(asUser(EDITOR, "Alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("가".repeat(250) + " (사본)"));
    }

    /**
     * 하위 포함 복제(W23) — 계층을 그대로 옮긴다.
     *
     * 사본 표시는 뿌리에만 붙인다. 하위까지 제목을 바꾸면 문서 안의 `[[제목]]`이 전부 어긋난다.
     */
    @Test
    void 하위_포함_복제는_계층을_보존하고_사본_표시는_뿌리에만_붙인다() throws Exception {
        Page root = pages.save(Page.of(spaceId, null, "안내", "루트", EDITOR));
        Page mid = pages.save(Page.of(spaceId, root.getId(), "배포", "중간", EDITOR));
        pages.save(Page.of(spaceId, mid.getId(), "롤백", "잎", EDITOR));

        long copyId = copy(root.getId(), "{\"includeDescendants\":true}");

        Page copiedRoot = pages.findById(copyId).orElseThrow();
        assertThat(copiedRoot.getTitle()).isEqualTo("안내 (사본)");
        List<Page> copiedChildren = pages.findByParentId(copyId);
        assertThat(copiedChildren).singleElement()
                .satisfies(child -> assertThat(child.getTitle()).isEqualTo("배포"));
        assertThat(pages.findByParentId(copiedChildren.get(0).getId())).singleElement()
                .satisfies(leaf -> assertThat(leaf.getTitle()).isEqualTo("롤백"));
        // 원본은 그대로다
        assertThat(pages.findByParentId(root.getId())).singleElement()
                .satisfies(child -> assertThat(child.getId()).isEqualTo(mid.getId()));
    }

    /**
     * 볼 수 없는 문서는 사본에 넣지 않는다. 사용자는 애초에 그 문서의 존재를 모르며,
     * 몰래 복사해 열어 두는 쪽이 훨씬 나쁘다.
     */
    @Test
    void 볼_수_없는_하위는_복제되지_않는다() throws Exception {
        Page root = pages.save(Page.of(spaceId, null, "안내", "루트", EDITOR));
        pages.save(Page.of(spaceId, root.getId(), "공개", "", EDITOR));
        Page closed = pages.save(Page.of(spaceId, root.getId(), "비밀", "", EDITOR));
        // 제한은 "볼 수 있는 사람" 목록이다 — EDITOR만 넣으면 VIEWER에게서 가려진다.
        restrictions.save(com.platform.wikibackend.domain.PageRestriction.of(
                closed.getId(), com.platform.wikibackend.domain.PageRestriction.Type.VIEW,
                com.platform.wikibackend.domain.PageRestriction.PrincipalType.USER, EDITOR, EDITOR));
        perms.allow(VIEWER, spaceId, WikiAction.EDIT);

        long copyId = copyAs(VIEWER, "Bob", root.getId(), "{\"includeDescendants\":true}");

        assertThat(pages.findByParentId(copyId))
                .extracting(Page::getTitle)
                .containsExactly("공개");
    }

    /**
     * 제한은 기본으로 함께 복사한다 — 제한된 문서의 사본이 열려 있으면 복사 한 번으로
     * 스페이스 전체에 내용이 열린다.
     */
    @Test
    void 제한은_기본으로_함께_복사된다() throws Exception {
        Page source = pages.save(Page.of(spaceId, null, "비밀", "본문", EDITOR));
        restrictions.save(com.platform.wikibackend.domain.PageRestriction.of(
                source.getId(), com.platform.wikibackend.domain.PageRestriction.Type.VIEW,
                com.platform.wikibackend.domain.PageRestriction.PrincipalType.USER, EDITOR, EDITOR));

        long copyId = copy(source.getId(), "{}");

        assertThat(restrictions.findByPageId(copyId))
                .singleElement()
                .satisfies(r -> assertThat(r.getPrincipalId()).isEqualTo(EDITOR));
    }

    @Test
    void 제한_복사를_명시적으로_끄면_사본은_열린다() throws Exception {
        Page source = pages.save(Page.of(spaceId, null, "비밀", "본문", EDITOR));
        restrictions.save(com.platform.wikibackend.domain.PageRestriction.of(
                source.getId(), com.platform.wikibackend.domain.PageRestriction.Type.VIEW,
                com.platform.wikibackend.domain.PageRestriction.PrincipalType.USER, EDITOR, EDITOR));

        long copyId = copy(source.getId(), "{\"includeRestrictions\":false}");

        assertThat(restrictions.findByPageId(copyId)).isEmpty();
    }

    private long copy(long pageId, String body) throws Exception {
        return copyAs(EDITOR, "Alice", pageId, body);
    }

    private long copyAs(long userId, String name, long pageId, String body) throws Exception {
        String res = mvc.perform(post("/api/wiki/pages/{id}/copy", pageId)
                        .with(asUser(userId, name))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(res).get("id").asLong();
    }
}
