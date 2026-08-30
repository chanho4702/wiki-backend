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

    public byte[] render(String exportTitle, List<Doc> docs) {
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

        org.w3c.dom.Document w3c = new W3CDom().fromJsoup(Jsoup.parse(page));
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(() -> MarkdownPdfRenderer.class.getResourceAsStream("/fonts/NanumGothic-Regular.ttf"),
                    "NanumGothic", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> MarkdownPdfRenderer.class.getResourceAsStream("/fonts/NanumGothic-Bold.ttf"),
                    "NanumGothic", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            builder.withW3cDocument(w3c, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("PDF 렌더링 실패", e);
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
