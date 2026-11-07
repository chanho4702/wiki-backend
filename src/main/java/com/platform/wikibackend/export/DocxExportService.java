package com.platform.wikibackend.export;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 페이지 Word(.docx) 내보내기 — PDF 내보내기(W26)와 같은 범위·권한(초안 가시성 포함)·이미지 규칙을
 * {@link ExportDocuments}에서 그대로 받는다. 다른 것은 렌더러뿐이다.
 */
@Service
@RequiredArgsConstructor
public class DocxExportService {

    private final ExportDocuments documents;
    private final MarkdownDocxRenderer renderer;

    public record Export(String filename, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public Export export(long userId, long pageId, boolean includeChildren) {
        ExportDocuments.Collected collected = documents.collect(userId, pageId, includeChildren);
        List<MarkdownDocxRenderer.Doc> docs = collected.docs().stream()
                .map(d -> new MarkdownDocxRenderer.Doc(d.title(), d.markdown()))
                .toList();
        byte[] docx = renderer.render(collected.rootTitle(), docs, id -> documents.image(userId, id));
        return new Export(collected.rootTitle() + ".docx", docx);
    }
}
