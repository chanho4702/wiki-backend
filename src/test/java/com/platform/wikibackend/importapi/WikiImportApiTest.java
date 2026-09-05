package com.platform.wikibackend.importapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.attachment.AttachmentLifecycleStatus;
import com.platform.wikibackend.config.InternalTokenFilter;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.AuditAction;
import com.platform.wikibackend.domain.AuditLog;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageComment;
import com.platform.wikibackend.domain.PageRestriction;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.event.RecordingEventPublisher;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.AttachmentVersionRepository;
import com.platform.wikibackend.repository.AuditLogRepository;
import com.platform.wikibackend.repository.NotificationRepository;
import com.platform.wikibackend.repository.PageCommentRepository;
import com.platform.wikibackend.repository.PageLabelRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRestrictionRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.PageWatchRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내부 import API(W29 X1, 설계 §2)의 계약 검증.
 *
 * 이 테스트가 지키는 것은 두 가지다.
 * 1. **문이 닫혀 있다** — 토큰 없이는 아무것도 못 한다. 이 경로에는 사용자 권한 검사가 없으므로
 *    토큰 검사가 유일한 방어선이고, 회귀하면 이관용 우회로가 그대로 열린다.
 * 2. **원본이 그대로 들어간다** — 시각·작성자 표시·리비전 번호·첨부 지문은 이관의 존재 이유다.
 *    "옮기긴 했는데 전부 오늘 날짜"가 되면 옮기지 않은 것과 같다.
 *
 * 요청·응답 예시는 `src/test/resources/fixtures/import-api/`에 있고, 이 테스트가 그 파일을
 * 실제 요청 본문으로 쓴다 — migration-service가 같은 파일로 가짜 위키 서버를 만들 수 있게,
 * 픽스처가 코드와 갈라지면 여기서 깨지도록 묶어 둔다. 픽스처의 `-1`은 "테스트가 실제 id로
 * 갈아 끼운다"는 자리 표시다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "platform.wiki.internal-token=test-internal-token")
class WikiImportApiTest {

    private static final String BASE = "/internal/wiki/import";
    private static final String TOKEN = "test-internal-token";
    private static final long ACTOR = 42L;
    private static final long MAPPED_AUTHOR = 77L;

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired PageLabelRepository labels;
    @Autowired PageCommentRepository comments;
    @Autowired PageRestrictionRepository restrictions;
    @Autowired AttachmentRepository attachments;
    @Autowired AttachmentVersionRepository attachmentVersions;
    @Autowired AuditLogRepository auditLogs;
    @Autowired NotificationRepository notifications;
    @Autowired PageWatchRepository watches;
    @Autowired RecordingEventPublisher events;

    MockMvc mvc;
    long spaceId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        restrictions.deleteAll();
        attachmentVersions.deleteAll();
        attachments.deleteAll();
        comments.deleteAll();
        labels.deleteAll();
        revisions.deleteAll();
        watches.deleteAll();
        notifications.deleteAll();
        auditLogs.deleteAll();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        events.reset();
        spaceId = spaces.save(Space.of("ENG", "엔지니어링", null, ACTOR)).getId();
    }

    // ── 문 ──

    @Test
    void 토큰이_없으면_403() throws Exception {
        mvc.perform(post(BASE + "/pages")
                        .header(InternalTokenFilter.ACTOR_HEADER, ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 잘못된_토큰이면_403() throws Exception {
        mvc.perform(post(BASE + "/pages")
                        .header(InternalTokenFilter.TOKEN_HEADER, "틀린-토큰")
                        .header(InternalTokenFilter.ACTOR_HEADER, ACTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 조회도_토큰이_있어야_한다() throws Exception {
        mvc.perform(get(BASE + "/spaces/{id}", spaceId)).andExpect(status().isForbidden());
    }

    @Test
    void actor_헤더가_없으면_400() throws Exception {
        MvcResult result = mvc.perform(post(BASE + "/pages")
                        .header(InternalTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPageBody().toString()))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(result).path("error").asText()).contains(InternalTokenFilter.ACTOR_HEADER);
    }

    @Test
    void actor_헤더가_숫자가_아니면_400() throws Exception {
        mvc.perform(post(BASE + "/pages")
                        .header(InternalTokenFilter.TOKEN_HEADER, TOKEN)
                        .header(InternalTokenFilter.ACTOR_HEADER, "관리자")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPageBody().toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 오류_본문은_플랫폼_계약을_지킨다() throws Exception {
        MvcResult result = mvc.perform(internal(get(BASE + "/pages/{id}", 999999L)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(body(result).has("error")).isTrue();
    }

    // ── 페이지 생성 ──

    @Test
    void 페이지_생성은_원본의_시각과_작성자_표시를_그대로_남긴다() throws Exception {
        JsonNode response = create();

        Page page = pages.findById(response.path("pageId").asLong()).orElseThrow();
        assertThat(page.getCreatedAt()).isEqualTo(Instant.parse("2019-03-04T05:06:07Z"));
        assertThat(page.getUpdatedAt()).isEqualTo(Instant.parse("2021-08-09T10:11:12Z"));
        // authorId를 안 보냈으므로 우리 쪽 책임자는 잡 요청자이고, 원본 사람은 표시 이름으로만 남는다.
        assertThat(page.getCreatedBy()).isEqualTo(ACTOR);
        assertThat(page.getImportedAuthorName()).isEqualTo("Jane Confluence");
        assertThat(page.getImportedSourceUrl()).contains("pageId=1234");
        assertThat(page.getSortOrder()).isEqualTo(3L);
        assertThat(labels.findByPageIdOrderByName(page.getId()))
                .extracting(l -> l.getName()).containsExactly("설계", "이관");
    }

    @Test
    void 대조된_작성자는_이관됨_표시를_남기지_않는다() throws Exception {
        ObjectNode body = createPageBody();
        body.put("authorId", MAPPED_AUTHOR);
        JsonNode response = postJson("/pages", body);

        Page page = pages.findById(response.path("pageId").asLong()).orElseThrow();
        assertThat(page.getCreatedBy()).isEqualTo(MAPPED_AUTHOR);
        assertThat(page.getImportedAuthorName()).isNull();
        assertThat(page.getImportedSourceUrl()).isNull();
    }

    @Test
    void 지난_버전은_리비전_1부터_k로_깔리고_현재본이_k플러스1이_된다() throws Exception {
        JsonNode response = create();
        long pageId = response.path("pageId").asLong();

        assertThat(response.path("version").asInt()).isEqualTo(3);
        List<PageRevision> history = revisions.findByPageIdOrderByVersionDesc(pageId);
        assertThat(history).extracting(PageRevision::getVersion).containsExactly(3, 2, 1);

        PageRevision first = history.get(2);
        assertThat(first.getContent()).isEqualTo("첫 판");
        assertThat(first.getEditedByName()).isEqualTo("Jane Confluence");
        assertThat(first.getCreatedAt()).isEqualTo(Instant.parse("2019-03-04T05:06:07Z"));

        PageRevision second = history.get(1);
        assertThat(second.getChangeNote()).isEqualTo("표 추가");
        assertThat(second.getCreatedAt()).isEqualTo(Instant.parse("2020-01-02T03:04:05Z"));
    }

    @Test
    void 리비전_순서는_version으로_정한다() throws Exception {
        ObjectNode body = createPageBody();
        // 원본 목록이 최신부터 오는 사이트가 있다 — 받은 순서를 그대로 믿으면 이력이 거꾸로 읽힌다.
        JsonNode reversed = json.createArrayNode()
                .add(body.path("revisions").get(1).deepCopy())
                .add(body.path("revisions").get(0).deepCopy());
        body.set("revisions", reversed);

        long pageId = postJson("/pages", body).path("pageId").asLong();
        assertThat(revisions.findByPageIdOrderByVersionDesc(pageId))
                .extracting(PageRevision::getContent)
                .containsExactly(body.path("content").asText(), "두 번째 판", "첫 판");
    }

    @Test
    void 리비전_편집자를_대조했으면_그_사람_id로_남는다() throws Exception {
        ObjectNode body = createPageBody();
        ((ObjectNode) body.path("revisions").get(0)).put("editorId", MAPPED_AUTHOR);

        long pageId = postJson("/pages", body).path("pageId").asLong();
        assertThat(revisions.findByPageIdAndVersion(pageId, 1).orElseThrow().getEditedBy())
                .isEqualTo(MAPPED_AUTHOR);
        assertThat(revisions.findByPageIdAndVersion(pageId, 2).orElseThrow().getEditedBy())
                .isEqualTo(ACTOR);
    }

    @Test
    void 쓰기는_색인_이벤트만_쏘고_알림도_자동구독도_없다() throws Exception {
        create();

        assertThat(events.events).hasSize(1);
        assertThat(events.events.get(0).hasPageCreated()).isTrue();
        assertThat(notifications.count()).isZero();
        assertThat(watches.count()).isZero();
    }

    @Test
    void 문서_한_건당_감사_기록은_한_줄이다() throws Exception {
        long pageId = create().path("pageId").asLong();
        // 뒤따르는 정리 작업은 기록하지 않는다 — 남기면 한 번의 이관이 감사 목록을 통째로 덮는다.
        putJson("/pages/" + pageId + "/order", fixture("reorder.request.json"));
        postJson("/pages/" + pageId + "/comments", fixture("create-comment.request.json"));

        List<AuditLog> logs = auditLogs.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAction()).isEqualTo(AuditAction.IMPORTED.name());
        assertThat(logs.get(0).getTargetId()).isEqualTo(pageId);
    }

    @Test
    void 없는_스페이스로_보내면_404() throws Exception {
        ObjectNode body = createPageBody();
        body.put("spaceId", 999999L);
        mvc.perform(internal(post(BASE + "/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString())))
                .andExpect(status().isNotFound());
    }

    // ── 재이관 ──

    @Test
    void 재이관은_새_리비전을_쌓고_수정시각을_원본으로_되돌린다() throws Exception {
        long pageId = create().path("pageId").asLong();

        JsonNode response = putJson("/pages/" + pageId, fixture("reimport-page.request.json"));

        assertThat(response.path("version").asInt()).isEqualTo(4);
        Page page = pages.findById(pageId).orElseThrow();
        assertThat(page.getTitle()).isEqualTo("설계 문서 (개정)");
        assertThat(page.getUpdatedAt()).isEqualTo(Instant.parse("2022-02-03T04:05:06Z"));
        assertThat(page.getCreatedAt()).isEqualTo(Instant.parse("2019-03-04T05:06:07Z"));
        assertThat(page.getImportedSourceUrl()).contains("pageId=1234");

        PageRevision latest = revisions.findByPageIdAndVersion(pageId, 4).orElseThrow();
        assertThat(latest.getChangeNote()).isEqualTo("이관 재실행");
        assertThat(labels.findByPageIdOrderByName(pageId))
                .extracting(l -> l.getName()).containsExactly("설계");
    }

    // ── 본문 재작성 ──

    @Test
    void bumpVersion이_false면_버전을_올리지_않고_현재_리비전_본문도_함께_눌린다() throws Exception {
        long pageId = create().path("pageId").asLong();
        ObjectNode body = fixture("rewrite-content.request.json");

        JsonNode response = putJson("/pages/" + pageId + "/content", body);

        assertThat(response.path("changed").asBoolean()).isTrue();
        assertThat(response.path("version").asInt()).isEqualTo(3);
        assertThat(revisions.findByPageIdOrderByVersionDesc(pageId)).hasSize(3);
        // 현재 버전의 리비전이 옛 본문을 들고 있으면 복원이 첨부 참조를 되돌려 깨뜨린다.
        assertThat(revisions.findByPageIdAndVersion(pageId, 3).orElseThrow().getContent())
                .isEqualTo(body.path("content").asText());
    }

    @Test
    void 본문이_같으면_아무것도_하지_않는다() throws Exception {
        JsonNode created = create();
        long pageId = created.path("pageId").asLong();
        ObjectNode body = json.createObjectNode()
                .put("content", createPageBody().path("content").asText())
                .put("bumpVersion", false);

        assertThat(putJson("/pages/" + pageId + "/content", body).path("changed").asBoolean()).isFalse();
    }

    @Test
    void bumpVersion이_true면_새_리비전이_쌓인다() throws Exception {
        long pageId = create().path("pageId").asLong();
        ObjectNode body = fixture("rewrite-content.request.json");
        body.put("bumpVersion", true);
        body.put("changeNote", "이관 링크 정리");

        JsonNode response = putJson("/pages/" + pageId + "/content", body);

        assertThat(response.path("version").asInt()).isEqualTo(4);
        assertThat(revisions.findByPageIdAndVersion(pageId, 4).orElseThrow().getChangeNote())
                .isEqualTo("이관 링크 정리");
    }

    // ── 순번 ──

    @Test
    void 순번만_바꾸면_리비전도_색인_이벤트도_생기지_않는다() throws Exception {
        long pageId = create().path("pageId").asLong();
        events.reset();

        JsonNode response = putJson("/pages/" + pageId + "/order", fixture("reorder.request.json"));

        assertThat(response.path("changed").asBoolean()).isTrue();
        assertThat(response.path("sortOrder").asLong()).isEqualTo(7L);
        assertThat(revisions.findByPageIdOrderByVersionDesc(pageId)).hasSize(3);
        assertThat(events.events).isEmpty();
    }

    // ── 첨부 ──

    @Test
    void 첨부는_처음이면_CREATED_같은_파일이면_UNCHANGED_다르면_NEW_VERSION() throws Exception {
        long pageId = create().path("pageId").asLong();
        byte[] first = png("첫 번째 그림");
        byte[] second = png("두 번째 그림");

        JsonNode created = upload(pageId, "그림.png", first);
        assertThat(created.path("outcome").asText()).isEqualTo("CREATED");
        long attachmentId = created.path("attachmentId").asLong();
        assertThat(created.path("inlineUrl").asText())
                .isEqualTo("/api/wiki/attachments/" + attachmentId + "/inline");
        assertThat(created.path("downloadUrl").asText())
                .isEqualTo("/api/wiki/attachments/" + attachmentId);

        JsonNode unchanged = upload(pageId, "그림.png", first);
        assertThat(unchanged.path("outcome").asText()).isEqualTo("UNCHANGED");
        assertThat(unchanged.path("attachmentId").asLong()).isEqualTo(attachmentId);
        assertThat(attachmentVersions.findByAttachmentIdOrderByVersionDesc(attachmentId)).isEmpty();

        JsonNode replaced = upload(pageId, "그림.png", second);
        assertThat(replaced.path("outcome").asText()).isEqualTo("NEW_VERSION");
        // 본문 참조가 첨부 id로 걸리므로 갈아끼운다 — 새 행을 만들면 문서에 옛 파일이 계속 보인다.
        assertThat(replaced.path("attachmentId").asLong()).isEqualTo(attachmentId);
        assertThat(attachmentVersions.findByAttachmentIdOrderByVersionDesc(attachmentId)).hasSize(1);

        Attachment row = attachments.findById(attachmentId).orElseThrow();
        assertThat(row.getChecksumSha256()).isEqualTo(sha256(second));
        assertThat(row.getContentType()).isEqualTo("image/png");
        assertThat(row.getLifecycleStatus()).isEqualTo(AttachmentLifecycleStatus.CONFIRMED);
    }

    @Test
    void checksum이_실제_내용과_다르면_400() throws Exception {
        long pageId = create().path("pageId").asLong();
        mvc.perform(internal(attachmentRequest(pageId, "그림.png", png("그림")))
                        .param("checksum", "f".repeat(64)))
                .andExpect(status().isBadRequest());
    }

    // ── 댓글 ──

    @Test
    void 댓글은_작성자_이름_스냅샷과_원본_시각으로_들어가고_답글이_달린다() throws Exception {
        long pageId = create().path("pageId").asLong();

        long parentId = postJson("/pages/" + pageId + "/comments",
                fixture("create-comment.request.json")).path("commentId").asLong();

        ObjectNode reply = fixture("create-comment.request.json");
        reply.put("parentCommentId", parentId);
        reply.put("authorId", MAPPED_AUTHOR);
        reply.put("body", "답글이다.");
        long replyId = postJson("/pages/" + pageId + "/comments", reply).path("commentId").asLong();

        PageComment parent = comments.findById(parentId).orElseThrow();
        assertThat(parent.getAuthorId()).isEqualTo(ACTOR);
        assertThat(parent.getAuthorName()).isEqualTo("Jane Confluence");
        assertThat(parent.getCreatedAt()).isEqualTo(Instant.parse("2020-05-06T07:08:09Z"));

        PageComment child = comments.findById(replyId).orElseThrow();
        assertThat(child.getParentId()).isEqualTo(parentId);
        // 대조된 작성자는 우리 사용자로 보여야 한다 — 원본 이름 스냅샷은 못 찾았을 때만 남는다.
        // (댓글은 표시 이름이 null일 수 없어 CommentService가 우리 쪽 폴백을 채운다.)
        assertThat(child.getAuthorId()).isEqualTo(MAPPED_AUTHOR);
        assertThat(child.getAuthorName()).isEqualTo("사용자 #" + MAPPED_AUTHOR);
        assertThat(notifications.count()).isZero();
    }

    @Test
    void 댓글_존재_조회는_없으면_404() throws Exception {
        long pageId = create().path("pageId").asLong();
        long commentId = postJson("/pages/" + pageId + "/comments",
                fixture("create-comment.request.json")).path("commentId").asLong();

        JsonNode view = getJson("/comments/" + commentId);
        assertThat(view.path("pageId").asLong()).isEqualTo(pageId);
        assertShape(view, fixture("comment.response.json"));

        comments.deleteById(commentId);
        mvc.perform(internal(get(BASE + "/comments/{id}", commentId)))
                .andExpect(status().isNotFound());
    }

    // ── 제한 ──

    @Test
    void 제한은_받은_목록을_그대로_건다() throws Exception {
        long pageId = create().path("pageId").asLong();

        mvc.perform(internal(put(BASE + "/pages/{id}/restrictions", pageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture("replace-restrictions.request.json").toString())))
                .andExpect(status().isNoContent());

        List<PageRestriction> rows = restrictions.findByPageId(pageId);
        assertThat(rows).hasSize(3);
        assertThat(rows).filteredOn(r -> r.getType() == PageRestriction.Type.VIEW)
                .extracting(r -> r.getPrincipalType().name() + ":" + r.getPrincipalId())
                .containsExactlyInAnyOrder("USER:11", "TEAM:22");
        assertThat(rows).filteredOn(r -> r.getType() == PageRestriction.Type.EDIT)
                .extracting(PageRestriction::getPrincipalId).containsExactly(11L);
    }

    @Test
    void 알_수_없는_주체_타입은_400() throws Exception {
        long pageId = create().path("pageId").asLong();
        String body = "{\"view\":[{\"type\":\"GROUP\",\"id\":1}],\"edit\":[]}";
        mvc.perform(internal(put(BASE + "/pages/{id}/restrictions", pageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isBadRequest());
        assertThat(restrictions.findByPageId(pageId)).isEmpty();
    }

    // ── 검증 조회 ──

    @Test
    void 검증_조회는_첨부_지문과_댓글_수까지_돌려준다() throws Exception {
        long pageId = create().path("pageId").asLong();
        byte[] bytes = png("그림");
        upload(pageId, "그림.png", bytes);
        postJson("/pages/" + pageId + "/comments", fixture("create-comment.request.json"));

        JsonNode view = getJson("/pages/" + pageId);

        assertThat(view.path("title").asText()).isEqualTo("설계 문서");
        assertThat(view.path("type").asText()).isEqualTo("page");
        assertThat(view.path("version").asInt()).isEqualTo(3);
        assertThat(view.path("sortOrder").asLong()).isEqualTo(3L);
        assertThat(view.path("contentLength").asInt())
                .isEqualTo(createPageBody().path("content").asText().length());
        assertThat(view.path("commentCount").asLong()).isEqualTo(1L);
        assertThat(view.path("labels").size()).isEqualTo(2);
        assertThat(view.path("attachments").get(0).path("checksum").asText())
                .isEqualTo(sha256(bytes));
        assertShape(view, fixture("page-view.response.json"));
    }

    @Test
    void 제목으로_찾으면_중복은_여러_건으로_온다() throws Exception {
        create();
        create();

        JsonNode matches = getJson("/spaces/" + spaceId + "/pages?title=설계 문서");
        assertThat(matches.path("pages").size()).isEqualTo(2);
        assertShape(matches, fixture("page-matches.response.json"));

        assertThat(getJson("/spaces/" + spaceId + "/pages?title=없는 제목").path("pages").size()).isZero();
    }

    @Test
    void 스페이스_조회는_존재와_이름을_알려준다() throws Exception {
        JsonNode space = getJson("/spaces/" + spaceId);
        assertThat(space.path("key").asText()).isEqualTo("ENG");
        assertThat(space.path("name").asText()).isEqualTo("엔지니어링");
        assertShape(space, fixture("space.response.json"));

        mvc.perform(internal(get(BASE + "/spaces/{id}", 999999L))).andExpect(status().isNotFound());
    }

    // ── 계약 픽스처 ──

    @Test
    void 응답_픽스처는_실제_응답과_같은_모양이다() throws Exception {
        JsonNode created = create();
        assertShape(created, fixture("create-page.response.json"));
        long pageId = created.path("pageId").asLong();

        assertShape(putJson("/pages/" + pageId, fixture("reimport-page.request.json")),
                fixture("reimport-page.response.json"));
        assertShape(putJson("/pages/" + pageId + "/content", fixture("rewrite-content.request.json")),
                fixture("rewrite-content.response.json"));
        assertShape(putJson("/pages/" + pageId + "/order", fixture("reorder.request.json")),
                fixture("reorder.response.json"));
        assertShape(upload(pageId, "그림.png", png("그림")), fixture("attachment.response.json"));
        assertShape(postJson("/pages/" + pageId + "/comments", fixture("create-comment.request.json")),
                fixture("create-comment.response.json"));
    }

    // ── 도우미 ──

    private JsonNode create() throws Exception {
        return postJson("/pages", createPageBody());
    }

    /** 픽스처의 자리 표시(-1)를 이 테스트의 실제 스페이스로 갈아 끼운다. */
    private ObjectNode createPageBody() {
        ObjectNode body = fixture("create-page.request.json");
        body.put("spaceId", spaceId);
        return body;
    }

    private ObjectNode fixture(String name) {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/import-api/" + name)) {
            assertThat(in).as("픽스처 없음: " + name).isNotNull();
            return (ObjectNode) json.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("픽스처를 읽지 못했습니다: " + name, e);
        }
    }

    private JsonNode postJson(String path, JsonNode body) throws Exception {
        return body(mvc.perform(internal(MockMvcRequestBuilders.post(BASE + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString())))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode putJson(String path, JsonNode body) throws Exception {
        return body(mvc.perform(internal(MockMvcRequestBuilders.put(BASE + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString())))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode getJson(String path) throws Exception {
        return body(mvc.perform(internal(get(BASE + path)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode upload(long pageId, String filename, byte[] content) throws Exception {
        return body(mvc.perform(internal(attachmentRequest(pageId, filename, content))
                        .param("checksum", sha256(content)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private MockMultipartHttpServletRequestBuilder attachmentRequest(long pageId, String filename,
                                                                     byte[] content) {
        MockMultipartHttpServletRequestBuilder request =
                multipart(BASE + "/pages/{id}/attachments", pageId);
        request.file(new MockMultipartFile("file", filename, "image/png", content));
        request.param("filename", filename);
        request.param("contentType", "image/png");
        return request;
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        request.header(InternalTokenFilter.TOKEN_HEADER, TOKEN);
        request.header(InternalTokenFilter.ACTOR_HEADER, ACTOR);
        return request;
    }

    /** multipart 빌더는 MockHttpServletRequestBuilder를 상속하지 않는다(Spring 6.2) — 따로 받는다. */
    private static MockMultipartHttpServletRequestBuilder internal(
            MockMultipartHttpServletRequestBuilder request) {
        request.header(InternalTokenFilter.TOKEN_HEADER, TOKEN);
        request.header(InternalTokenFilter.ACTOR_HEADER, ACTOR);
        return request;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    /**
     * 실제 응답과 픽스처의 **필드 이름**이 같은지 본다. 값(특히 id)은 실행마다 달라 비교할 수
     * 없지만, 필드가 늘거나 사라지는 것이 계약 드리프트의 실제 모습이다.
     */
    private static void assertShape(JsonNode actual, JsonNode expected) {
        assertThat(fieldNames(actual)).as("응답 필드 집합").isEqualTo(fieldNames(expected));
        expected.fieldNames().forEachRemaining(name -> {
            JsonNode e = expected.get(name);
            JsonNode a = actual.get(name);
            if (e.isObject() && a.isObject()) {
                assertShape(a, e);
            } else if (e.isArray() && a.isArray() && !e.isEmpty() && !a.isEmpty()
                    && e.get(0).isObject() && a.get(0).isObject()) {
                assertShape(a.get(0), e.get(0));
            }
        });
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        java.util.Collections.sort(names);
        return names;
    }

    /** PNG 매직으로 시작하는 바이트 — 서버가 형식을 바이트에서 다시 판정한다. */
    private static byte[] png(String marker) {
        byte[] magic = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        byte[] tail = marker.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[magic.length + tail.length];
        System.arraycopy(magic, 0, out, 0, magic.length);
        System.arraycopy(tail, 0, out, magic.length, tail.length);
        return out;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
