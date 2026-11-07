package com.platform.wikibackend.mdimport;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 목적지 구간만 바꾸는지 — CommonMark 목적지 규칙(괄호 균형·이스케이프·꺾쇠·제목)과 참조 정의의 이미지 판정. */
class MarkdownLinkRewriterTest {

    /** 받은 목적지·이미지 여부를 기록하고 "NEW(목적지|img)"로 바꾼다. */
    private static final class Recorder implements MarkdownLinkRewriter.Resolver {
        final List<String> seen = new ArrayList<>();

        @Override
        public Optional<String> resolve(String destination, boolean image) {
            seen.add(destination + (image ? " img" : " link"));
            return Optional.of("NEW{" + destination.replace("(", "_").replace(")", "_") + (image ? "|img" : "") + "}");
        }
    }

    @Test
    void 괄호가_든_목적지와_제목은_CommonMark대로_목적지_전체를_본다() {
        Recorder r = new Recorder();
        String out = MarkdownLinkRewriter.rewrite("[spec](spec(v2).pdf \"제목\") 끝", r);
        assertThat(r.seen).containsExactly("spec(v2).pdf link");
        assertThat(out).isEqualTo("[spec](NEW{spec_v2_.pdf} \"제목\") 끝");
    }

    @Test
    void 이스케이프와_꺾쇠_목적지() {
        Recorder r = new Recorder();
        String out = MarkdownLinkRewriter.rewrite("[a](a\\(1\\).md) [b](<b c.md> 'T')", r);
        assertThat(r.seen).containsExactly("a(1).md link", "b c.md link");
        assertThat(out).isEqualTo("[a](NEW{a_1_.md}) [b](NEW{b c.md} 'T')"); // 꺾쇠까지 바꾼다
    }

    @Test
    void 참조_정의는_그_라벨을_이미지가_쓰면_이미지로_푼다() {
        Recorder r = new Recorder();
        String out = MarkdownLinkRewriter.rewrite("""
                ![d][pic] [문서][doc] ![축약]

                [PIC]: img/a.png
                [doc]: guide.md
                [축약]: img/b.png
                """, r);
        assertThat(r.seen).containsExactlyInAnyOrder("img/a.png img", "guide.md link", "img/b.png img");
        assertThat(out).contains("[PIC]: NEW{img/a.png|img}").contains("[doc]: NEW{guide.md}");
    }

    @Test
    void 같은_라벨을_이미지와_링크가_함께_쓰면_이미지로_푼다() {
        Recorder r = new Recorder();
        MarkdownLinkRewriter.rewrite("![그림][p] [원본 보기][p]\n\n[p]: a.png\n", r);
        assertThat(r.seen).containsExactly("a.png img");
    }

    @Test
    void 코드_안의_링크와_나머지_글자는_그대로다() {
        Recorder r = new Recorder();
        String source = """
                :::columns
                `[코드](x.md)` [진짜](y.md)

                ```
                [펜스](z.md)
                ```

                | 표 | [칸](t.md) |
                | --- | --- |
                $$
                E=mc^2
                $$
                """;
        String out = MarkdownLinkRewriter.rewrite(source, r);
        assertThat(r.seen).containsExactlyInAnyOrder("y.md link", "t.md link");
        assertThat(out).isEqualTo(source.replace("(y.md)", "(NEW{y.md})").replace("(t.md)", "(NEW{t.md})"));
    }

    @Test
    void 바꾸지_않으면_원문_그대로() {
        String source = "[a](https://example.com) ![b](c.png)";
        assertThat(MarkdownLinkRewriter.rewrite(source, (d, i) -> Optional.empty())).isSameAs(source);
    }
}
