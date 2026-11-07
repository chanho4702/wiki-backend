package com.platform.wikibackend.mdimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.wikibackend.TestPages;
import com.platform.wikibackend.domain.Attachment;
import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.domain.Space;
import com.platform.wikibackend.permission.FakePermissionClient;
import com.platform.wikibackend.permission.WikiAction;
import com.platform.wikibackend.repository.AttachmentRepository;
import com.platform.wikibackend.repository.PageRepository;
import com.platform.wikibackend.repository.PageRevisionRepository;
import com.platform.wikibackend.repository.SpaceRepository;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.platform.wikibackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Markdown zip 가져오기 — 트리·index·폴더, 첨부·문서 링크 재작성, zip 안전 규칙, 전부 아니면 전무. */
@SpringBootTest
@ActiveProfiles("test")
class MarkdownZipImportTest {

    @Autowired WebApplicationContext context;
    @Autowired SpaceRepository spaces;
    @Autowired PageRepository pages;
    @Autowired PageRevisionRepository revisions;
    @Autowired AttachmentRepository attachments;
    @Autowired FakePermissionClient perms;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    MockMvc mvc;

    Space space;
    static final long USER = 1L;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestPages.deleteAll(jdbc);
        spaces.deleteAll();
        perms.reset();
        space = spaces.save(Space.of("dev", "개발", null, USER));
        perms.allow(USER, space.getId(), WikiAction.VIEW);
        perms.allow(USER, space.getId(), WikiAction.EDIT);
    }

    private static byte[] png() throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** 이름 → 내용(문자열은 UTF-8). */
    private static byte[] zip(Map<String, Object> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                zip.putArchiveEntry(new ZipArchiveEntry(e.getKey()));
                zip.write(e.getValue() instanceof byte[] b ? b : ((String) e.getValue()).getBytes(StandardCharsets.UTF_8));
                zip.closeArchiveEntry();
            }
        }
        return out.toByteArray();
    }

    private ResultActions upload(byte[] zip, String... params) throws Exception {
        var request = multipart("/api/wiki/spaces/{spaceId}/import/markdown", space.getId())
                .file(new MockMultipartFile("files", "docs.zip", "application/zip", zip));
        for (int i = 0; i + 1 < params.length; i += 2) request.param(params[i], params[i + 1]);
        return mvc.perform(request.with(asUser(USER, "김")));
    }

    private List<Page> pagesInSpace() {
        return pages.findAll().stream()
                .filter(p -> p.getSpaceId().equals(space.getId()))
                .sorted(Comparator.comparing(Page::getId))
                .toList();
    }

    private Page byTitle(String title) {
        return pagesInSpace().stream().filter(p -> p.getTitle().equals(title)).findFirst().orElseThrow();
    }

    @Test
    void 폴더_구조가_트리가_되고_상대_링크는_첨부와_새_문서_링크로_바뀐다() throws Exception {
        Page parent = pages.save(Page.of(space.getId(), null, "가져온 묶음", "", USER));
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("guide/index.md", "# 가이드\n\n[설치](install.md) ![그림](img/a.png) [명세](<files/spec sheet.pdf>) "
                + "[또 그림](img/a.png) [없음](nope.md) [외부](https://example.com) `[코드](install.md)`\n\n"
                + "```\n[펜스 안](install.md)\n```\n");
        entries.put("guide/install.md", "# 설치\n\n[가이드로](./index.md#top) [루트](../readme.md) [폴더](../notes/)\n\n[참조][r]\n\n[r]: <../readme.md> \"제목\"\n");
        entries.put("guide/img/a.png", png());
        entries.put("guide/files/spec sheet.pdf", "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII));
        entries.put("notes/memo.md", "메모 본문");
        entries.put("readme.md", "# 읽어 주세요\n");
        entries.put("unused.txt", "아무도 안 가리킴");
        entries.put("__MACOSX/guide/._index.md", "메타");
        entries.put(".DS_Store", "x");

        String body = upload(zip(entries), "parentId", String.valueOf(parent.getId()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode result = new ObjectMapper().readTree(body);

        // 생성 순서(트리 전위) — 첫 문서가 묶음의 첫 최상위
        assertThat(result.get("pages").findValuesAsText("title"))
                .containsExactly("가이드", "설치", "notes", "memo", "읽어 주세요");
        Page guide = byTitle("가이드");
        Page install = byTitle("설치");
        Page notes = byTitle("notes");
        Page memo = byTitle("memo");
        Page readme = byTitle("읽어 주세요");
        assertThat(guide.getParentId()).isEqualTo(parent.getId());
        assertThat(guide.getType()).isEqualTo(PageType.PAGE);       // index.md가 있는 폴더 = 그 문서
        assertThat(install.getParentId()).isEqualTo(guide.getId());
        assertThat(notes.getType()).isEqualTo(PageType.FOLDER);     // index 없는 폴더 = 폴더
        assertThat(notes.getParentId()).isEqualTo(parent.getId());
        assertThat(memo.getParentId()).isEqualTo(notes.getId());
        assertThat(readme.getParentId()).isEqualTo(parent.getId());
        assertThat(pagesInSpace()).noneMatch(p -> p.getTitle().equals("img") || p.getTitle().equals("files"));

        List<Attachment> guideFiles = attachments.findByPageId(guide.getId());
        assertThat(guideFiles).extracting(Attachment::getFilename).containsExactlyInAnyOrder("a.png", "spec sheet.pdf");
        Attachment image = guideFiles.stream().filter(a -> a.getFilename().equals("a.png")).findFirst().orElseThrow();
        Attachment pdf = guideFiles.stream().filter(a -> a.getFilename().equals("spec sheet.pdf")).findFirst().orElseThrow();
        assertThat(image.getContentType()).isEqualTo("image/png");

        String s = "/spaces/" + space.getId() + "/pages/";
        assertThat(guide.getContent())
                .contains("[설치](" + s + install.getId() + ")")
                .contains("![그림](/api/wiki/attachments/" + image.getId() + "/inline)")
                .contains("[또 그림](/api/wiki/attachments/" + image.getId() + ")") // 링크로 가리키면 내려받기 주소
                .contains("[명세](/api/wiki/attachments/" + pdf.getId() + ")")
                .contains("[없음](nope.md)")                    // 못 찾은 링크는 그대로
                .contains("[외부](https://example.com)")
                .contains("`[코드](install.md)`")               // 인라인 코드는 링크가 아니다
                .contains("[펜스 안](install.md)");             // 코드 펜스도
        assertThat(install.getContent())
                .contains("[가이드로](" + s + guide.getId() + "#top)")
                .contains("[루트](" + s + readme.getId() + ")")
                .contains("[폴더](" + s + notes.getId() + ")")
                .contains("[r]: " + s + readme.getId() + " \"제목\"");     // 참조 정의도

        // 마무리는 새 편집이 아니다 — v1 그대로, v1 리비전도 같은 본문
        assertThat(guide.getVersion()).isEqualTo(1);
        assertThat(revisions.findByPageIdAndVersion(guide.getId(), 1).orElseThrow().getContent())
                .isEqualTo(guide.getContent());

        assertThat(result.get("warnings").findValuesAsText("message"))
                .anySatisfy(m -> assertThat(m).contains("찾지 못한 상대 링크 1개"))
                .anySatisfy(m -> assertThat(m).contains("가리키지 않은 파일 1개"));
    }

    @Test
    void 초안으로_가져오면_문서는_초안이고_폴더는_게시_상태다() throws Exception {
        upload(zip(Map.of("a/b.md", "# 비")), "status", "draft").andExpect(status().isCreated());
        assertThat(byTitle("비").getStatus()).isEqualTo(PageStatus.DRAFT);
        assertThat(byTitle("a").getType()).isEqualTo(PageType.FOLDER);
    }

    @Test
    void zip_slip_항목은_거부하고_아무것도_만들지_않는다() throws Exception {
        upload(zip(Map.of("ok.md", "# 좋음", "../evil.md", "# 나쁨")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("상위 경로")));
        upload(zip(Map.of("/etc/evil.md", "# 나쁨")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("절대 경로")));
        upload(zip(Map.of("C:/evil.md", "# 나쁨"))).andExpect(status().isBadRequest());
        upload(zip(Map.of("a\\..\\..\\evil.md", "# 나쁨"))).andExpect(status().isBadRequest());
        assertThat(pagesInSpace()).isEmpty();
    }

    @Test
    void 심볼릭_링크_항목은_거부한다() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            zip.putArchiveEntry(new ZipArchiveEntry("a.md"));
            zip.write("# 에이".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
            ZipArchiveEntry link = new ZipArchiveEntry("secret.md");
            link.setUnixMode(0120777); // S_IFLNK
            zip.putArchiveEntry(link);
            zip.write("/etc/passwd".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        upload(out.toByteArray())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("심볼릭 링크")));
        assertThat(pagesInSpace()).isEmpty();
    }

    @Test
    void 압축_폭탄_상한_항목_수와_풀린_크기() throws Exception {
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i <= 1000; i++) many.put("f" + i + ".md", "x");
        upload(zip(many))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("항목이 너무 많습니다")));

        // 선언 크기가 아니라 실제로 풀린 바이트로 센다 — 1MB를 넘는 .md(압축하면 몇 KB)
        byte[] big = new byte[(int) MarkdownImportService.MAX_FILE_BYTES + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        upload(zip(Map.of("big.md", big)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("너무 큽니다")));
        assertThat(pagesInSpace()).isEmpty();
    }

    @Test
    void Markdown이_없거나_다른_파일과_섞이면_거부한다() throws Exception {
        upload(zip(Map.of("a.png", png())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Markdown 파일")));
        mvc.perform(multipart("/api/wiki/spaces/{spaceId}/import/markdown", space.getId())
                        .file(new MockMultipartFile("files", "a.zip", "application/zip", zip(Map.of("a.md", "a"))))
                        .file(new MockMultipartFile("files", "b.md", "text/markdown", "b".getBytes(StandardCharsets.UTF_8)))
                        .with(asUser(USER, "김")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("zip 파일은 한 번에 하나")));
        upload("zip 아님".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("zip 파일을 읽을 수 없습니다")));
        assertThat(pagesInSpace()).isEmpty();
    }

    /** Codex P2 — 참조형 이미지가 내려받기 주소로, 괄호가 든 목적지가 잘려 엉뚱한 첨부로 가던 것. */
    @Test
    void 참조형_이미지는_inline이고_괄호가_든_목적지는_통째로_푼다() throws Exception {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("doc.md", "![그림][pic] [명세](files/spec(v2).pdf)\n\n[pic]: img/a.png\n");
        entries.put("img/a.png", png());
        entries.put("files/spec(v2).pdf", "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII));
        entries.put("files/spec", "괄호 앞에서 잘리면 여기로 간다");
        upload(zip(entries)).andExpect(status().isCreated());

        Page doc = byTitle("doc");
        List<Attachment> files = attachments.findByPageId(doc.getId());
        assertThat(files).extracting(Attachment::getFilename).containsExactlyInAnyOrder("a.png", "spec(v2).pdf");
        Attachment image = files.stream().filter(a -> a.getFilename().equals("a.png")).findFirst().orElseThrow();
        Attachment pdf = files.stream().filter(a -> a.getFilename().equals("spec(v2).pdf")).findFirst().orElseThrow();
        assertThat(doc.getContent())
                .contains("[pic]: /api/wiki/attachments/" + image.getId() + "/inline")
                .contains("[명세](/api/wiki/attachments/" + pdf.getId() + ")");
    }

    @Test
    void 같은_이름의_다른_파일은_한_문서에서_이름을_바꿔_붙인다() throws Exception {
        upload(zip(Map.of(
                "doc.md", "![a](a/x.png) ![b](b/x.png)",
                "a/x.png", png(),
                "b/x.png", png()))).andExpect(status().isCreated());
        Page doc = byTitle("doc");
        assertThat(attachments.findByPageId(doc.getId())).extracting(Attachment::getFilename)
                .containsExactlyInAnyOrder("x.png", "x (2).png");
    }

    @Test
    void 항목_이름_정규화_단위() {
        assertThat(MarkdownZipImporter.normalizeEntryName("./a//b/c.md")).isEqualTo("a/b/c.md");
        assertThat(MarkdownZipImporter.normalizeEntryName("a\\b.md")).isEqualTo("a/b.md");
        assertThat(MarkdownZipImporter.normalizeEntryName("__MACOSX/a.md")).isNull();
        assertThat(MarkdownZipImporter.normalizeEntryName(".git/config")).isNull();
        // macOS zip의 NFD 한글 이름은 NFC로
        String nfd = java.text.Normalizer.normalize("가이드.md", java.text.Normalizer.Form.NFD);
        assertThat(MarkdownZipImporter.normalizeEntryName(nfd)).isEqualTo("가이드.md");
    }
}
