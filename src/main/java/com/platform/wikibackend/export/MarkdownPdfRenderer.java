package com.platform.wikibackend.export;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 마크다운 문서 묶음 → PDF 바이트(W26).
 *
 * 파이프라인: 전처리(지시자 정리) → flexmark(MD→HTML, GFM 표·취소선·체크박스) → jsoup(XHTML 정규화,
 * openhtmltopdf는 잘 닫힌 XML만 받는다) → openhtmltopdf(PDF, PDFBox 3).
 *
 * 한글은 폰트를 심지 않으면 전부 빈 네모다 — NanumGothic(OFL)을 리소스로 품고 PDF에 임베드한다.
 * 원본 마크다운의 raw HTML은 이스케이프한다(보기 렌더러와 같은 XSS 정책 — PDF라고 예외가 아니다).
 */
@Component
public class MarkdownPdfRenderer {

    /** 컨테이너/리프 지시자 줄(:::columns, ::toc, ::excerpt-include[..] 등) — PDF에서는 마커만 걷어낸다.
     *  :::properties 안의 표처럼 **내용은 남는다**. */
    private static final Pattern DIRECTIVE_LINE = Pattern.compile("(?m)^\\s*(\\\\?:){2,}[^\\n]*$");
    private static final Pattern STATUS_TOKEN = Pattern.compile("\\\\?:status\\[([^\\]]*)\\](?:\\{[^}]*\\})?");
    private static final Pattern MENTION_LINK = Pattern.compile("\\[(@[^\\]]*)\\]\\(user:\\d+\\)");
    private static final Pattern DATE_LINK = Pattern.compile("\\[([^\\]]*)\\]\\(date:\\d{4}-\\d{2}-\\d{2}\\)");

    private final Parser parser;
    private final HtmlRenderer html;

    public MarkdownPdfRenderer() {
        MutableDataSet options = new MutableDataSet()
                .set(Parser.EXTENSIONS, List.of(
                        TablesExtension.create(), StrikethroughExtension.create(), TaskListExtension.create()))
                .set(HtmlRenderer.ESCAPE_HTML, true);
        this.parser = Parser.builder(options).build();
        this.html = HtmlRenderer.builder(options).build();
    }

    public record Doc(String title, String markdown) {
    }

    /**
     * 위키 첨부 이미지 해석기 — 첨부 id를 받아 PDF에 심을 data URI를 준다. 볼 수 없거나 이미지가 아니면
     * 빈 값이다(그 이미지는 대체 텍스트로 바뀐다). 렌더러는 이것 말고는 어떤 주소도 열지 않는다.
     */
    @FunctionalInterface
    public interface ImageSource {
        java.util.Optional<String> dataUri(long attachmentId);

        ImageSource NONE = id -> java.util.Optional.empty();
    }

    /** 본문이 쓰는 위키 첨부 참조(인라인·내려받기) — 상대 경로만. 호스트가 붙은 주소는 외부 URL로 본다. */
    private static final Pattern WIKI_ATTACHMENT = Pattern.compile("^/api/wiki/attachments/(\\d+)(?:/inline)?$");

    public byte[] render(String exportTitle, List<Doc> docs) {
        return render(exportTitle, docs, ImageSource.NONE);
    }

    public byte[] render(String exportTitle, List<Doc> docs, ImageSource images) {
        StringBuilder body = new StringBuilder();
        for (Doc doc : docs) {
            body.append("<section class=\"doc\"><h1 class=\"doc-title\">")
                    .append(org.jsoup.nodes.Entities.escape(doc.title()))
                    .append("</h1>")
                    .append(html.render(parser.parse(preprocess(doc.markdown()))))
                    .append("</section>");
        }
        String page = "<html><head><style>" + CSS + "</style><title>"
                + org.jsoup.nodes.Entities.escape(exportTitle) + "</title></head><body>" + body + "</body></html>";

        org.jsoup.nodes.Document parsed = Jsoup.parse(page);
        inlineImages(parsed, images);
        org.w3c.dom.Document w3c = new W3CDom().fromJsoup(parsed);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(() -> MarkdownPdfRenderer.class.getResourceAsStream("/fonts/NanumGothic-Regular.ttf"),
                    "NanumGothic", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> MarkdownPdfRenderer.class.getResourceAsStream("/fonts/NanumGothic-Bold.ttf"),
                    "NanumGothic", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            // SSRF 차단(감사 W-06): 기본 스트림 구현은 http(s)·file URL을 서버에서 연다. 이미지는 위에서 전부
            // data URI로 바꿨거나 지웠으므로, data: 외의 모든 URI는 해석하지 않는다(두 겹째 방어).
            builder.useUriResolver((base, uri) -> uri != null && uri.startsWith("data:") ? uri : null);
            builder.useProtocolsStreamImplementation(url -> {
                throw new SecurityException("PDF 렌더러는 외부 주소를 열지 않습니다: " + url);
            }, "http", "https", "file", "ftp", "jar");
            builder.withW3cDocument(w3c, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("PDF 렌더링 실패", e);
        }
    }

    /**
     * 모든 &lt;img&gt;를 정리한다. 위키 첨부 참조면 해석기가 준 data URI로 바꾸고, 그 밖의 주소(외부 URL,
     * 프로토콜 상대, file: 등)나 해석기가 거절한 첨부는 대체 텍스트로 바꾼다 — 렌더러가 열 주소가 남지 않는다.
     */
    static void inlineImages(org.jsoup.nodes.Document doc, ImageSource images) {
        for (org.jsoup.nodes.Element img : doc.select("img")) {
            String src = img.attr("src").trim();
            java.util.regex.Matcher m = WIKI_ATTACHMENT.matcher(src);
            java.util.Optional<String> data = java.util.Optional.empty();
            if (m.matches()) {
                try {
                    data = images.dataUri(Long.parseLong(m.group(1)));
                } catch (NumberFormatException ignored) {
                    // 범위를 넘는 id — 그냥 대체 텍스트
                }
            }
            if (data.isPresent()) {
                img.attr("src", data.get());
            } else {
                String alt = img.attr("alt");
                img.replaceWith(new org.jsoup.nodes.TextNode(alt.isBlank() ? "[이미지]" : "[이미지: " + alt + "]"));
            }
        }
    }

    /** 위키 고유 문법을 PDF에서 읽히는 형태로 — 지시자 마커 제거, 배지·멘션·날짜는 글자만. */
    static String preprocess(String markdown) {
        if (markdown == null) return "";
        String s = markdown;
        s = STATUS_TOKEN.matcher(s).replaceAll("$1");
        s = MENTION_LINK.matcher(s).replaceAll("$1");
        s = DATE_LINK.matcher(s).replaceAll("$1");
        s = DIRECTIVE_LINE.matcher(s).replaceAll("");
        return s;
    }

    /** A4·여백 20mm·NanumGothic. 표·코드가 화면과 완전히 같을 필요는 없다 — 읽히는 문서면 된다. */
    private static final String CSS = """
            @page { size: A4; margin: 20mm; }
            body { font-family: 'NanumGothic'; font-size: 10.5pt; line-height: 1.65; color: #172b4d; }
            .doc { page-break-after: always; }
            .doc:last-child { page-break-after: auto; }
            .doc-title { font-size: 20pt; margin: 0 0 12pt; border-bottom: 2pt solid #dfe1e6; padding-bottom: 6pt; }
            h1 { font-size: 16pt; } h2 { font-size: 14pt; } h3 { font-size: 12pt; }
            table { border-collapse: collapse; width: 100%; margin: 8pt 0; }
            th, td { border: 0.6pt solid #c1c7d0; padding: 4pt 6pt; text-align: left; }
            th { background: #f4f5f7; }
            code { background: #f4f5f7; padding: 0 2pt; font-size: 9.5pt; }
            pre { background: #f4f5f7; padding: 8pt; font-size: 9.5pt; white-space: pre-wrap; word-wrap: break-word; }
            blockquote { border-left: 2pt solid #c1c7d0; margin: 8pt 0; padding: 0 0 0 10pt; color: #5e6c84; }
            img { max-width: 100%; }
            a { color: #0052cc; text-decoration: none; }
            hr { border: 0; border-top: 0.6pt solid #dfe1e6; }
            """;
}
