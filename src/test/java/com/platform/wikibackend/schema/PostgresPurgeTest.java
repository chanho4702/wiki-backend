package com.platform.wikibackend.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.PostgresTestSupport;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "되돌릴 수 없는 삭제"가 정말 아무것도 남기지 않는가(감사 W-05) — 실제 PostgreSQL 스키마에서.
 *
 * 영구 삭제와 스페이스 삭제가 지난 첨부 버전 객체, 리액션(FK 없음), 공동 편집 상태(room 키, FK 없음)를
 * 남겼다. 기능 증상이 없어 발견이 어렵고, 개인정보 삭제 요청에 응할 수 없게 만든다.
 */
class PostgresPurgeTest extends PostgresTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();
    static final long ALICE = 1L;

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired FakePermissionClient perms;
    @Autowired JdbcTemplate jdbc;
    @Value("${platform.wiki.files-dir}") String filesDir;
    MockMvc mvc;
    Space space;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        perms.reset();
        space = spaces.save(Space.of("pgp" + SEQ.incrementAndGet(), "PG 삭제", null, ALICE));
        perms.allow(ALICE, space.getId(), WikiAction.VIEW);
        perms.allow(ALICE, space.getId(), WikiAction.EDIT);
        perms.allow(ALICE, space.getId(), WikiAction.ADMIN);
    }

    @Test
    void 영구_삭제는_지난_첨부_버전_리액션_공동_편집_상태까지_지운다() throws Exception {
        long pageId = createPage("영구 삭제 대상");
        List<Path> files = decorate(pageId);

        mvc.perform(delete("/api/wiki/pages/" + pageId).with(asUser(ALICE, "앨리스")))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(delete("/api/wiki/pages/" + pageId + "/purge").with(asUser(ALICE, "앨리스")))
                .andExpect(status().isNoContent());

        assertNothingLeft(pageId, files);
    }

    @Test
    void 스페이스_삭제도_같은_것을_지운다() throws Exception {
        long pageId = createPage("스페이스와 함께");
        List<Path> files = decorate(pageId);

        mvc.perform(delete("/api/wiki/spaces/" + space.getId()).with(asUser(ALICE, "앨리스")))
                .andExpect(status().isNoContent());

        assertNothingLeft(pageId, files);
    }

    /** 첨부 두 버전·리액션(문서·댓글)·공동 편집 상태·방문·별표를 붙이고, 저장소 파일 경로를 돌려준다. */
    private List<Path> decorate(long pageId) throws Exception {
        upload(pageId, "diagram.png", png((byte) 1));
        upload(pageId, "diagram.png", png((byte) 2)); // 같은 이름 → 지난 버전이 생긴다
        mvc.perform(put("/api/wiki/pages/{id}/reactions/{e}", pageId, "👍").with(asUser(ALICE, "앨리스")))
                .andExpect(status().is2xxSuccessful());
        String comment = mvc.perform(post("/api/wiki/pages/" + pageId + "/comments").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"댓글\"}"))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        long commentId = JSON.readTree(comment).get("id").asLong();
        mvc.perform(put("/api/wiki/comments/{id}/reactions/{e}", commentId, "🎉").with(asUser(ALICE, "앨리스")))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(post("/api/wiki/pages/" + pageId + "/views").with(asUser(ALICE, "앨리스")))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(put("/api/wiki/pages/" + pageId + "/star").with(asUser(ALICE, "앨리스")))
                .andExpect(status().is2xxSuccessful());
        jdbc.update("insert into collaboration_document (room, state, base_page_version, generation)"
                + " values (?, ?, 1, 1)", "page:" + pageId, new byte[] {1, 2, 3});

        List<Path> files = jdbc.queryForList("""
                select storage_key from attachment where page_id = ?
                union all
                select v.storage_key from attachment_version v join attachment a on a.id = v.attachment_id
                where a.page_id = ?""", String.class, pageId, pageId)
                .stream().map(key -> Path.of(filesDir).resolve(key)).toList();
        assertThat(files).hasSize(2);
        assertThat(files).allMatch(Files::exists);
        assertThat(count("select count(*) from reaction where target_type = 'COMMENT' and target_id = ?", commentId))
                .isEqualTo(1);
        return files;
    }

    private void assertNothingLeft(long pageId, List<Path> files) {
        assertThat(count("select count(*) from page where id = ?", pageId)).isZero();
        assertThat(count("select count(*) from attachment where page_id = ?", pageId)).isZero();
        assertThat(count("select count(*) from attachment_version v where not exists"
                + " (select 1 from attachment a where a.id = v.attachment_id)")).isZero();
        assertThat(count("select count(*) from reaction where target_type = 'PAGE' and target_id = ?", pageId)).isZero();
        assertThat(count("select count(*) from reaction r where r.target_type = 'COMMENT' and not exists"
                + " (select 1 from page_comment c where c.id = r.target_id)")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from collaboration_document where room = ?",
                Integer.class, "page:" + pageId)).isZero();
        assertThat(count("select count(*) from page_visit where page_id = ?", pageId)).isZero();
        assertThat(count("select count(*) from page_star where page_id = ?", pageId)).isZero();
        assertThat(files).noneMatch(Files::exists); // 지난 버전 파일까지 저장소에서 사라졌다
    }

    private long createPage(String title) throws Exception {
        String body = mvc.perform(post("/api/wiki/pages").with(asUser(ALICE, "앨리스"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"spaceId\":" + space.getId() + ",\"title\":\"" + title + "\",\"content\":\"본문\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    private void upload(long pageId, String filename, byte[] bytes) throws Exception {
        mvc.perform(multipart("/api/wiki/pages/{id}/attachments", pageId)
                        .file(new MockMultipartFile("file", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, bytes))
                        .with(asUser(ALICE, "앨리스")))
                .andExpect(status().isCreated());
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static byte[] png(byte marker) {
        byte[] header = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        byte[] out = new byte[header.length + 1];
        System.arraycopy(header, 0, out, 0, header.length);
        out[header.length] = marker;
        return out;
    }
}
