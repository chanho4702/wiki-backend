package com.platform.wikibackend.page;

import com.platform.wikibackend.domain.Page;
import com.platform.wikibackend.domain.PageRevision;
import com.platform.wikibackend.domain.RevisionContent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리비전 본문 압축(V16) — 이력을 전부 보관하기로 했으므로 크기가 곧 비용이다(2026-08-28 결정).
 * 저장 형식이 둘(평문·gzip)이라도 읽는 쪽은 하나만 본다.
 */
class RevisionCompressionTest {

    private static Page pageWith(String content) {
        return Page.of(1L, null, "제목", content, 1L);
    }

    @Test
    void 긴_본문은_압축해서_담고_읽을_때_원문_그대로_돌려준다() {
        String content = "배포 절차 안내.\n".repeat(200);

        PageRevision revision = PageRevision.snapshotOf(pageWith(content));

        assertThat(revision.getContentGzip()).isNotNull();
        assertThat(revision.getContentText()).isNull();
        assertThat(revision.getContent()).isEqualTo(content);
    }

    /** 짧은 본문은 gzip 헤더 때문에 오히려 커진다 — 압축하지 않는 편이 맞다. */
    @Test
    void 짧은_본문은_평문으로_담는다() {
        PageRevision revision = PageRevision.snapshotOf(pageWith("짧은 메모"));

        assertThat(revision.getContentGzip()).isNull();
        assertThat(revision.getContentText()).isEqualTo("짧은 메모");
        assertThat(revision.getContent()).isEqualTo("짧은 메모");
    }

    @Test
    void 압축은_실제로_크기를_줄인다() {
        String content = "배포는 금요일에 한다. 승인자는 팀 리드다.\n".repeat(300);

        byte[] compressed = RevisionContent.compress(content);

        assertThat(compressed.length)
                .isLessThan(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length / 4);
        assertThat(RevisionContent.decompress(compressed)).isEqualTo(content);
    }

    /** 한글·이모지가 왕복에서 깨지면 이력이 조용히 손상된다. */
    @Test
    void 멀티바이트_문자도_왕복한다() {
        String content = ("한글 본문 🚀 émoji ✅ ".repeat(100));

        assertThat(RevisionContent.decompress(RevisionContent.compress(content))).isEqualTo(content);
    }

    @Test
    void 빈_본문도_왕복한다() {
        PageRevision revision = PageRevision.snapshotOf(pageWith(""));

        assertThat(revision.getContent()).isEmpty();
    }
}
