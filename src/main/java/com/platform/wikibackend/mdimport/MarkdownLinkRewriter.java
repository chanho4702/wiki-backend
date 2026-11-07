package com.platform.wikibackend.mdimport;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 마크다운 본문의 링크·이미지 목적지만 바꿔 쓴다 — 나머지 글자는 한 글자도 건드리지 않는다.
 *
 * 대상: 인라인 `[글](목적지 "제목")`·`![대체](목적지)`와 참조 정의 `[이름]: 목적지`. 코드 펜스(``` / ~~~) 안과
 * 인라인 코드(`…`) 안은 링크가 아니므로 그대로 둔다. AST로 다시 직렬화하지 않는 이유: 원문의 공백·서식·위키 확장
 * 문법(`:::`·`$$`)을 그대로 보존해야 한다 — 저장 형식이 마크다운 문자열 자체다.
 *
 * 줄 단위로 처리한다. 한 링크가 줄을 넘어가는 경우(목적지 앞 줄바꿈 등)는 드물어 바꾸지 않는다.
 */
final class MarkdownLinkRewriter {

    /** 목적지를 받아 새 목적지를 주거나, 바꾸지 않으려면 빈 값. image = `![…](…)`·참조형 이미지 여부를 모르는 정의는 false. */
    @FunctionalInterface
    interface Resolver {
        Optional<String> resolve(String destination, boolean image);
    }

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");
    private static final Pattern CODE_SPAN = Pattern.compile("(`+)[^`]*?\\1");
    /** 대괄호 한 단계 중첩까지 허용(`[a [b] c](x)`). 목적지는 `<…>` 또는 공백 없는 덩어리. */
    private static final Pattern INLINE = Pattern.compile(
            "(!?)\\[((?:[^\\[\\]\\\\]|\\\\.|\\[[^\\[\\]]*\\])*)\\]\\(([ \\t]*)(<[^>\\n]*>|[^\\s()<>]+)");
    private static final Pattern REFERENCE_DEF = Pattern.compile("^( {0,3}\\[[^\\]]+\\]:[ \\t]*)(<[^>\\n]*>|\\S+)");

    private MarkdownLinkRewriter() {
    }

    static String rewrite(String markdown, Resolver resolver) {
        StringBuilder out = new StringBuilder(markdown.length() + 64);
        String fence = null;
        int start = 0;
        while (start <= markdown.length()) {
            int end = markdown.indexOf('\n', start);
            String line = end < 0 ? markdown.substring(start) : markdown.substring(start, end);
            Matcher f = FENCE.matcher(line);
            if (fence != null) {
                if (f.find() && f.group(1).charAt(0) == fence.charAt(0) && f.group(1).length() >= fence.length()) {
                    fence = null;
                }
                out.append(line);
            } else if (f.find()) {
                fence = f.group(1);
                out.append(line);
            } else {
                out.append(rewriteLine(line, resolver));
            }
            if (end < 0) break;
            out.append('\n');
            start = end + 1;
        }
        return out.toString();
    }

    private static String rewriteLine(String line, Resolver resolver) {
        Matcher def = REFERENCE_DEF.matcher(line);
        if (def.find()) {
            String replaced = replaceDestination(def.group(2), false, resolver);
            return def.group(1) + replaced + line.substring(def.end());
        }
        // 인라인 코드 구간은 그대로 두고 그 사이만 바꾼다
        StringBuilder out = new StringBuilder(line.length());
        Matcher code = CODE_SPAN.matcher(line);
        int last = 0;
        while (code.find()) {
            out.append(rewriteInline(line.substring(last, code.start()), resolver)).append(code.group());
            last = code.end();
        }
        out.append(rewriteInline(line.substring(last), resolver));
        return out.toString();
    }

    private static String rewriteInline(String text, Resolver resolver) {
        Matcher m = INLINE.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start(4));
            out.append(replaceDestination(m.group(4), !m.group(1).isEmpty(), resolver));
            last = m.end(4);
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private static String replaceDestination(String raw, boolean image, Resolver resolver) {
        boolean angled = raw.startsWith("<") && raw.endsWith(">");
        String destination = angled ? raw.substring(1, raw.length() - 1) : raw;
        return resolver.resolve(destination, image).orElse(raw);
    }
}
