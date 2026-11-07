package com.platform.wikibackend.export;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ImageRef;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.LinkRef;
import com.vladsch.flexmark.ast.ListBlock;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.Reference;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListItem;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableBody;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 마크다운 문서 묶음 → Word(.docx) 바이트.
 *
 * 파이프라인: 전처리(PDF와 같은 {@link MarkdownPdfRenderer#preprocess} — 지시자 마커 제거, 배지·멘션·날짜는
 * 글자만) → flexmark AST(GFM 표·취소선·체크박스) → Apache POI XWPF. HTML을 거치지 않고 AST를 직접 옮긴다 —
 * Word 문단·표·제목 스타일로 바로 대응돼야 받는 사람이 Word에서 그대로 고쳐 쓸 수 있다.
 *
 * 대응: 제목(Heading 1~6 스타일, Word 탐색 창에 뜬다)·굵게/기울임/취소선/인라인 코드·링크(외부 http(s)·mailto만
 * 하이퍼링크, 위키 내부 상대 링크는 글자)·목록(들여쓰기 + 글머리/번호 글자)·체크박스(☐/☑)·인용·구분선·표·코드 블록.
 * Word가 그리지 못하는 확장은 원본을 남긴다: Mermaid·수식(`$$`)은 이름표를 붙인 코드 블록, raw HTML은 글자 그대로
 * (보기 렌더러와 같은 정책 — 렌더하지 않는다).
 *
 * 이미지: 위키 첨부만 {@link ImageSource}로 받아 문서에 심는다. 그 밖의 주소는 열지 않고 대체 텍스트로(SSRF,
 * 감사 W-06). 문서 하나에 심는 이미지 총량에도 상한을 둔다 — 넘으면 나머지는 대체 텍스트.
 */
@Component
public class MarkdownDocxRenderer {

    /** 한 파일에 심는 이미지 바이트 합계 상한(장당 상한은 ExportDocuments.MAX_IMAGE_BYTES). */
    static final long MAX_TOTAL_IMAGE_BYTES = 50L * 1024 * 1024;

    /** 본문 폭(A4 − 여백 2cm×2)에 맞춘 이미지 최대 폭, 포인트. */
    private static final double MAX_IMAGE_WIDTH_PT = 453;

    private static final String BODY_FONT = "Malgun Gothic";
    private static final String CODE_FONT = "Consolas";
    private static final String CODE_FILL = "F4F5F7";
    private static final String MUTED = "5E6C84";
    private static final String LINK = "0052CC";

    private static final Pattern WIKI_ATTACHMENT = Pattern.compile("^/api/wiki/attachments/(\\d+)(?:/inline)?$");
    private static final Pattern EXTERNAL_LINK = Pattern.compile("^(https?://|mailto:).+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPLAY_MATH = Pattern.compile("^\\$\\$([\\s\\S]+)\\$\\$$");

    private final Parser parser;

    public MarkdownDocxRenderer() {
        MutableDataSet options = new MutableDataSet()
                .set(Parser.EXTENSIONS, List.of(
                        TablesExtension.create(), StrikethroughExtension.create(), TaskListExtension.create()));
        this.parser = Parser.builder(options).build();
    }

    public record Doc(String title, String markdown) {
    }

    /** 위키 첨부 이미지 해석기 — 볼 수 없거나 심을 수 없는 이미지면 빈 값(대체 텍스트로 바뀐다). */
    @FunctionalInterface
    public interface ImageSource {
        Optional<ExportDocuments.Image> image(long attachmentId);

        ImageSource NONE = id -> Optional.empty();
    }

    public byte[] render(String exportTitle, List<Doc> docs, ImageSource images) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.getProperties().getCoreProperties().setTitle(exportTitle);
            defineStyles(doc);
            Writer writer = new Writer(doc, images);
            boolean first = true;
            for (Doc d : docs) {
                XWPFParagraph title = doc.createParagraph();
                title.setStyle("Title");
                if (!first) title.setPageBreak(true);
                first = false;
                writer.text(title.createRun(), d.title());
                writer.blocks(parser.parse(MarkdownPdfRenderer.preprocess(d.markdown())), 0);
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Word 문서 생성 실패", e);
        }
    }

    /** Word 탐색 창·목차가 알아보는 내장 스타일 id(Title, Heading1~6)를 정의한다. */
    private static void defineStyles(XWPFDocument doc) {
        XWPFStyles styles = doc.createStyles();
        addParagraphStyle(styles, "Title", "Title", 28, null);
        int[] sizes = {20, 16, 14, 12, 11, 11};
        for (int level = 1; level <= 6; level++) {
            addParagraphStyle(styles, "Heading" + level, "heading " + level, sizes[level - 1], level - 1);
        }
    }

    private static void addParagraphStyle(XWPFStyles styles, String id, String name, int sizePt, Integer outline) {
        CTStyle ct = CTStyle.Factory.newInstance();
        ct.setStyleId(id);
        ct.setType(STStyleType.PARAGRAPH);
        ct.addNewName().setVal(name);
        ct.addNewQFormat();
        var ppr = ct.addNewPPr();
        ppr.addNewKeepNext();
        var spacing = ppr.addNewSpacing();
        spacing.setBefore(BigInteger.valueOf(240));
        spacing.setAfter(BigInteger.valueOf(120));
        if (outline != null) ppr.addNewOutlineLvl().setVal(BigInteger.valueOf(outline));
        CTRPr rpr = ct.addNewRPr();
        rpr.addNewB();
        rpr.addNewSz().setVal(BigInteger.valueOf(sizePt * 2L));
        rpr.addNewColor().setVal("172B4D");
        styles.addStyle(new XWPFStyle(ct, styles));
    }

    /** 인라인 서식 상태 — 중첩(굵게 안의 링크 등)을 그대로 쌓는다. */
    private record Style(boolean bold, boolean italic, boolean strike, boolean code, String link) {
        static final Style PLAIN = new Style(false, false, false, false, null);

        Style withBold() { return new Style(true, italic, strike, code, link); }
        Style withItalic() { return new Style(bold, true, strike, code, link); }
        Style withStrike() { return new Style(bold, italic, true, code, link); }
        Style withCode() { return new Style(bold, italic, strike, true, link); }
        Style withLink(String url) { return new Style(bold, italic, strike, code, url); }
    }

    /** 한 문서 생성 동안의 상태(이미지 예산). */
    private static final class Writer {
        private final XWPFDocument doc;
        private final ImageSource images;
        private long imageBytes;

        Writer(XWPFDocument doc, ImageSource images) {
            this.doc = doc;
            this.images = images;
        }

        // ── 블록 ──

        void blocks(Node parent, int indent) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                block(node, indent);
            }
        }

        private void block(Node node, int indent) {
            if (node instanceof Heading h) {
                XWPFParagraph p = paragraph(indent);
                p.setStyle("Heading" + Math.min(Math.max(h.getLevel(), 1), 6));
                inlines(p, h, Style.PLAIN);
            } else if (node instanceof Paragraph para) {
                Matcher math = DISPLAY_MATH.matcher(para.getChars().toString().trim());
                if (math.matches()) {
                    codeBlock("수식 (LaTeX)", math.group(1).trim(), indent);
                } else {
                    inlines(paragraph(indent), para, Style.PLAIN);
                }
            } else if (node instanceof FencedCodeBlock code) {
                String info = code.getInfo().toString().trim().toLowerCase(Locale.ROOT);
                String label = info.startsWith("mermaid") ? "Mermaid 다이어그램 (Word에서는 원본 코드로 표시)"
                        : info.startsWith("math") || info.startsWith("latex") ? "수식 (LaTeX)" : null;
                codeBlock(label, stripTrailingNewline(code.getContentChars().toString()), indent);
            } else if (node instanceof IndentedCodeBlock code) {
                codeBlock(null, stripTrailingNewline(code.getContentChars().toString()), indent);
            } else if (node instanceof HtmlBlock html) {
                // raw HTML은 렌더하지 않는다(보기 렌더러와 같은 XSS 정책) — 원문 글자로 남긴다
                codeBlock(null, stripTrailingNewline(html.getChars().toString()), indent);
            } else if (node instanceof BlockQuote quote) {
                int before = doc.getParagraphs().size();
                blocks(quote, indent + 1);
                for (int i = before; i < doc.getParagraphs().size(); i++) {
                    XWPFParagraph p = doc.getParagraphs().get(i);
                    p.setBorderLeft(Borders.SINGLE);
                }
            } else if (node instanceof ListBlock list) {
                list(list, indent);
            } else if (node instanceof ThematicBreak) {
                XWPFParagraph p = paragraph(indent);
                p.setBorderBottom(Borders.SINGLE);
            } else if (node instanceof TableBlock table) {
                table(table);
            } else if (node.hasChildren()) {
                blocks(node, indent); // 알 수 없는 컨테이너 — 내용만 살린다
            }
        }

        private void list(ListBlock list, int indent) {
            int number = list instanceof OrderedList ol ? ol.getStartNumber() : 1;
            String[] bullets = {"•", "◦", "▪"};
            for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
                if (!(child instanceof ListItem item)) continue;
                String marker;
                if (item instanceof TaskListItem task) {
                    marker = task.isItemDoneMarker() ? "☑" : "☐";
                } else if (list instanceof BulletList) {
                    marker = bullets[indent % bullets.length];
                } else {
                    marker = number + ".";
                }
                number++;
                boolean firstBlock = true;
                for (Node inner = item.getFirstChild(); inner != null; inner = inner.getNext()) {
                    if (firstBlock && inner instanceof Paragraph para) {
                        XWPFParagraph p = paragraph(indent + 1);
                        p.setIndentationHanging(360);
                        text(p.createRun(), marker + "\t");
                        inlines(p, para, Style.PLAIN);
                    } else if (firstBlock) {
                        text(paragraph(indent + 1).createRun(), marker);
                        block(inner, indent + 1);
                    } else {
                        block(inner, indent + 1);
                    }
                    firstBlock = false;
                }
                if (firstBlock) text(paragraph(indent + 1).createRun(), marker); // 빈 항목
            }
        }

        private void codeBlock(String label, String code, int indent) {
            if (label != null) {
                XWPFParagraph caption = paragraph(indent);
                XWPFRun run = caption.createRun();
                text(run, label);
                run.setItalic(true);
                run.setColor(MUTED);
                run.setFontSize(9);
            }
            String[] lines = code.split("\n", -1);
            for (String line : lines) {
                XWPFParagraph p = paragraph(indent);
                p.setSpacingAfter(0);
                p.setSpacingBefore(0);
                shade(p);
                XWPFRun run = p.createRun();
                monospace(run);
                // 앞 공백을 살린다 — 들여쓴 코드가 한 줄로 붙지 않게
                run.setText(line.replace("\t", "    "));
                run.getCTR().getTArray(0).setSpace(org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute.Space.PRESERVE);
            }
            paragraph(indent).setSpacingAfter(0); // 코드 블록 뒤 빈 줄
        }

        private void table(TableBlock block) {
            List<TableRow> rows = new java.util.ArrayList<>();
            int cols = 0;
            for (Node section = block.getFirstChild(); section != null; section = section.getNext()) {
                if (!(section instanceof TableHead) && !(section instanceof TableBody)) continue;
                for (Node r = section.getFirstChild(); r != null; r = r.getNext()) {
                    if (r instanceof TableRow row) {
                        rows.add(row);
                        int n = 0;
                        for (Node c = row.getFirstChild(); c != null; c = c.getNext()) {
                            if (c instanceof TableCell) n++;
                        }
                        cols = Math.max(cols, n);
                    }
                }
            }
            if (rows.isEmpty() || cols == 0) return;
            XWPFTable table = doc.createTable(rows.size(), cols);
            table.setWidth("100%");
            table.setCellMargins(60, 100, 60, 100); // 위·왼·아래·오른(twip) — 글자가 테두리에 붙지 않게
            for (int r = 0; r < rows.size(); r++) {
                XWPFTableRow wordRow = table.getRow(r);
                boolean header = rows.get(r).getParent() instanceof TableHead;
                if (header) wordRow.setRepeatHeader(true);
                int c = 0;
                for (Node n = rows.get(r).getFirstChild(); n != null && c < cols; n = n.getNext()) {
                    if (!(n instanceof TableCell cell)) continue;
                    XWPFTableCell wordCell = wordRow.getCell(c++);
                    if (header) wordCell.setColor(CODE_FILL);
                    XWPFParagraph p = wordCell.getParagraphs().get(0);
                    if (cell.getAlignment() == TableCell.Alignment.CENTER) p.setAlignment(ParagraphAlignment.CENTER);
                    if (cell.getAlignment() == TableCell.Alignment.RIGHT) p.setAlignment(ParagraphAlignment.RIGHT);
                    inlines(p, cell, header ? Style.PLAIN.withBold() : Style.PLAIN);
                }
            }
            doc.createParagraph(); // 표 뒤 간격 — 연속 표가 한 표로 붙지 않게
        }

        private XWPFParagraph paragraph(int indent) {
            XWPFParagraph p = doc.createParagraph();
            if (indent > 0) p.setIndentationLeft(360 * indent);
            return p;
        }

        // ── 인라인 ──

        private void inlines(XWPFParagraph p, Node parent, Style style) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                inline(p, node, style);
            }
        }

        private void inline(XWPFParagraph p, Node node, Style style) {
            if (node instanceof Text t) {
                run(p, t.getChars().unescape(), style);
            } else if (node instanceof StrongEmphasis) {
                inlines(p, node, style.withBold());
            } else if (node instanceof Emphasis) {
                inlines(p, node, style.withItalic());
            } else if (node instanceof Strikethrough) {
                inlines(p, node, style.withStrike());
            } else if (node instanceof Code code) {
                run(p, code.getText().toString(), style.withCode());
            } else if (node instanceof Link link) {
                String url = link.getUrl().toString().trim();
                inlines(p, link, EXTERNAL_LINK.matcher(url).matches() ? style.withLink(url) : style);
            } else if (node instanceof AutoLink link) {
                String url = link.getUrl().toString().trim();
                run(p, url, EXTERNAL_LINK.matcher(url).matches() ? style.withLink(url) : style);
            } else if (node instanceof MailLink mail) {
                String address = mail.getText().toString();
                run(p, address, style.withLink("mailto:" + address));
            } else if (node instanceof Image img) {
                image(p, img.getUrl().toString().trim(), img.getText().toString());
            } else if (node instanceof ImageRef ref) {
                // 참조형 이미지 `![alt][ref]` — 정의(`[ref]: url`)로 풀어 인라인 이미지와 같은 길을 탄다
                Reference def = ref.getReferenceNode(ref.getDocument());
                if (def != null) {
                    image(p, def.getUrl().toString().trim(), ref.getText().toString());
                } else {
                    run(p, ref.getChars().unescape(), style); // 정의 없는 참조는 원문 글자
                }
            } else if (node instanceof LinkRef ref) {
                // 참조형 링크 `[t][ref]`·`[ref]` — 정의의 주소로 일반 링크와 같은 규칙(외부만 하이퍼링크)
                Reference def = ref.getReferenceNode(ref.getDocument());
                if (def == null) {
                    run(p, ref.getChars().unescape(), style);
                } else {
                    String url = def.getUrl().toString().trim();
                    Style linkStyle = EXTERNAL_LINK.matcher(url).matches() ? style.withLink(url) : style;
                    if (ref.hasChildren()) {
                        inlines(p, ref, linkStyle);
                    } else {
                        run(p, ref.getReference().unescape(), linkStyle);
                    }
                }
            } else if (node instanceof SoftLineBreak) {
                run(p, " ", style);
            } else if (node instanceof HardLineBreak) {
                p.createRun().addBreak();
            } else if (node instanceof HtmlInline html) {
                run(p, html.getChars().toString(), style); // 렌더하지 않고 글자로
            } else if (node.hasChildren()) {
                inlines(p, node, style);
            } else {
                run(p, node.getChars().unescape(), style);
            }
        }

        private void run(XWPFParagraph p, String text, Style style) {
            if (text.isEmpty()) return;
            XWPFRun run;
            if (style.link() != null) {
                XWPFHyperlinkRun link = p.createHyperlinkRun(style.link());
                link.setColor(LINK);
                link.setUnderline(UnderlinePatterns.SINGLE);
                run = link;
            } else {
                run = p.createRun();
            }
            if (style.bold()) run.setBold(true);
            if (style.italic()) run.setItalic(true);
            if (style.strike()) run.setStrikeThrough(true);
            if (style.code()) {
                monospace(run);
                CTRPr rpr = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
                CTShd shd = rpr.addNewShd();
                shd.setVal(STShd.CLEAR);
                shd.setFill(CODE_FILL);
                run.setText(text);
            } else {
                text(run, text);
            }
        }

        void text(XWPFRun run, String text) {
            run.setFontFamily(BODY_FONT);
            run.setText(text);
        }

        private void image(XWPFParagraph p, String src, String alt) {
            Matcher m = WIKI_ATTACHMENT.matcher(src);
            Optional<ExportDocuments.Image> found = Optional.empty();
            if (m.matches()) {
                try {
                    found = images.image(Long.parseLong(m.group(1)));
                } catch (NumberFormatException ignored) {
                    // 범위를 넘는 id — 대체 텍스트
                }
            }
            if (found.isPresent() && imageBytes + found.get().bytes().length <= MAX_TOTAL_IMAGE_BYTES
                    && embed(p, found.get(), alt)) {
                imageBytes += found.get().bytes().length;
                return;
            }
            XWPFRun run = p.createRun();
            text(run, alt.isBlank() ? "[이미지]" : "[이미지: " + alt + "]");
            run.setColor(MUTED);
        }

        private boolean embed(XWPFParagraph p, ExportDocuments.Image img, String alt) {
            int type = switch (img.contentType()) {
                case "image/png" -> Document.PICTURE_TYPE_PNG;
                case "image/jpeg" -> Document.PICTURE_TYPE_JPEG;
                case "image/gif" -> Document.PICTURE_TYPE_GIF;
                default -> -1;
            };
            if (type < 0) return false;
            try {
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(img.bytes()));
                if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) return false;
                // 96dpi 픽셀을 포인트로(0.75), 본문 폭을 넘으면 비율 유지 축소
                double widthPt = decoded.getWidth() * 0.75;
                double heightPt = decoded.getHeight() * 0.75;
                if (widthPt > MAX_IMAGE_WIDTH_PT) {
                    heightPt = heightPt * MAX_IMAGE_WIDTH_PT / widthPt;
                    widthPt = MAX_IMAGE_WIDTH_PT;
                }
                p.createRun().addPicture(new ByteArrayInputStream(img.bytes()), type,
                        alt.isBlank() ? "image" : alt, Units.toEMU(widthPt), Units.toEMU(heightPt));
                return true;
            } catch (IOException | org.apache.poi.openxml4j.exceptions.InvalidFormatException | RuntimeException e) {
                return false; // 깨진 이미지 하나로 내보내기 전체를 실패시키지 않는다
            }
        }

        private static void monospace(XWPFRun run) {
            run.setFontFamily(CODE_FONT);
            run.setFontFamily(BODY_FONT, XWPFRun.FontCharRange.eastAsia);
            run.setFontSize(9.5);
        }

        private static void shade(XWPFParagraph p) {
            var ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
            CTShd shd = ppr.isSetShd() ? ppr.getShd() : ppr.addNewShd();
            shd.setVal(STShd.CLEAR);
            shd.setFill(CODE_FILL);
        }

        private static String stripTrailingNewline(String s) {
            return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        }
    }
}
