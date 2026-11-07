package com.platform.wikibackend.mdimport;

import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ImageRef;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.LinkNodeBase;
import com.vladsch.flexmark.ast.Reference;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.sequence.BasedSequence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 마크다운 본문의 링크·이미지 목적지만 바꿔 쓴다 — 나머지 글자는 한 글자도 건드리지 않는다.
 *
 * 링크의 위치는 flexmark AST(CommonMark)로 찾는다. 정규식으로 찾으면 괄호가 든 목적지(`spec(v2).pdf`)·이스케이프·
 * `<…>` 감싼 목적지·제목 부분을 잘못 자른다(Codex P2). 코드 펜스·인라인 코드 안은 AST에 링크 노드가 생기지 않아 자연히
 * 빠진다. 바꾸는 것은 원문의 목적지 구간(`<…>`면 꺾쇠까지)뿐이라 공백·서식·위키 확장 문법(`:::`·`$$`)이 그대로 남는다 —
 * 저장 형식이 마크다운 문자열 자체이므로 AST를 다시 직렬화하지 않는다.
 *
 * 대상: 인라인 `[글](목적지 "제목")`·`![대체](목적지)`, 참조 정의 `[이름]: 목적지`. 참조 정의는 어떤 참조가 쓰는지에
 * 따라 이미지인지 정한다 — `![d][pic]`이 쓰는 `[pic]: img/a.png`는 이미지다. 같은 라벨을 이미지와 링크가 함께 쓰면
 * 이미지로 푼다(inline 주소는 이미지 형식이면 링크로 눌러도 열린다).
 */
final class MarkdownLinkRewriter {

    /** 목적지(이스케이프를 푼 값)를 받아 새 목적지를 주거나, 바꾸지 않으려면 빈 값. */
    @FunctionalInterface
    interface Resolver {
        Optional<String> resolve(String destination, boolean image);
    }

    /**
     * SPACE_IN_LINK_URLS: CommonMark는 `<b c.md>`처럼 꺾쇠로 감싼 목적지에 공백을 허용하는데, flexmark는 이 옵션이
     * 꺼져 있으면 그것을 링크로 보지 않는다. 내보낸 문서 묶음은 파일 이름에 공백이 흔하다.
     */
    private static final Parser PARSER = Parser.builder(new MutableDataSet()
            .set(Parser.EXTENSIONS, List.of(TablesExtension.create(), StrikethroughExtension.create()))
            .set(Parser.SPACE_IN_LINK_URLS, true))
            .build();

    private record Edit(int start, int end, String replacement) {
    }

    private MarkdownLinkRewriter() {
    }

    static String rewrite(String markdown, Resolver resolver) {
        Node document = PARSER.parse(markdown);

        Set<String> imageLabels = new HashSet<>();
        for (Node node : document.getDescendants()) {
            if (node instanceof ImageRef ref) {
                // 축약형 `![pic]`·`![pic][]`은 글 자체가 라벨이다
                BasedSequence label = ref.getReference().isBlank() ? ref.getText() : ref.getReference();
                imageLabels.add(normalizeLabel(label));
            }
        }

        List<Edit> edits = new ArrayList<>();
        for (Node node : document.getDescendants()) {
            if (node instanceof Image image) {
                collect(image, true, resolver, edits);
            } else if (node instanceof Link link) {
                collect(link, false, resolver, edits);
            } else if (node instanceof Reference reference) {
                collect(reference, imageLabels.contains(normalizeLabel(reference.getReference())), resolver, edits);
            }
        }
        if (edits.isEmpty()) return markdown;

        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder out = new StringBuilder(markdown);
        int lastStart = Integer.MAX_VALUE;
        for (Edit edit : edits) {
            if (edit.end() > lastStart) continue; // 겹치는 구간(있을 수 없지만) — 원문을 깨지 않게 건너뛴다
            out.replace(edit.start(), edit.end(), edit.replacement());
            lastStart = edit.start();
        }
        return out.toString();
    }

    private static void collect(LinkNodeBase node, boolean image, Resolver resolver, List<Edit> edits) {
        BasedSequence url = node.getUrl();
        if (url.isNull() || url.isBlank()) return;
        resolver.resolve(url.unescape(), image).ifPresent(replacement -> {
            BasedSequence open = node.getUrlOpeningMarker();
            BasedSequence close = node.getUrlClosingMarker();
            // `<…>`로 감싼 목적지는 꺾쇠까지 바꾼다 — 새 주소에는 공백이 없어 꺾쇠가 필요 없다
            int start = open.isNotNull() && !open.isEmpty() ? open.getStartOffset() : url.getStartOffset();
            int end = close.isNotNull() && !close.isEmpty() ? close.getEndOffset() : url.getEndOffset();
            edits.add(new Edit(start, end, replacement));
        });
    }

    /** CommonMark 라벨 대조 — 대소문자 무시, 연속 공백은 하나. */
    private static String normalizeLabel(CharSequence label) {
        return label.toString().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
