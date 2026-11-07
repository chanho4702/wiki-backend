package com.platform.wikibackend.export;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;

/**
 * 페이지 PDF 내보내기(W26) — 문서(선택 시 하위 포함)를 한 PDF로.
 *
 * 프론트 인쇄(window.print)로 대신하던 것을 서버 렌더로 바꿨다(2026-08-31 사용자 요청): 인쇄는
 * 브라우저·용지 설정에 따라 결과가 제각각이고, 하위 문서 묶음을 한 파일로 만들 수 없었다.
 *
 * 무엇을 담는지(권한·하위 포함·이미지 판정)는 Word 내보내기와 같은 {@link ExportDocuments}가 정한다.
 * 이미지는 data URI로만 들어간다 — 렌더러는 어떤 URL도 열지 않는다(SSRF, 감사 W-06).
 */
@Service
@RequiredArgsConstructor
public class PdfExportService {

    private final ExportDocuments documents;
    private final MarkdownPdfRenderer renderer;

    public record Export(String filename, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public Export export(long userId, long pageId, boolean includeChildren) {
        ExportDocuments.Collected collected = documents.collect(userId, pageId, includeChildren);
        List<MarkdownPdfRenderer.Doc> docs = collected.docs().stream()
                .map(d -> new MarkdownPdfRenderer.Doc(d.title(), d.markdown()))
                .toList();
        byte[] pdf = renderer.render(collected.rootTitle(), docs, id -> documents.image(userId, id)
                .map(img -> "data:" + img.contentType() + ";base64," + Base64.getEncoder().encodeToString(img.bytes())));
        return new Export(collected.rootTitle() + ".pdf", pdf);
    }
}
