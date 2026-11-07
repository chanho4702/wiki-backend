package com.platform.wikibackend.mdimport;

import com.platform.wikibackend.domain.PageStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static com.platform.wikibackend.space.SpaceController.userId;

/** Markdown 파일 가져오기 — 올린 .md 파일마다 새 문서 하나. */
@Tag(name = "Import", description = "Markdown 파일로 문서 만들기.")
@RestController
@RequiredArgsConstructor
public class MarkdownImportController {

    private final MarkdownImportService service;

    @Operation(summary = "Markdown 파일을 새 문서로 가져온다",
            description = "파일마다 문서 하나를 부모 아래(비우면 스페이스 루트) 형제 맨 뒤에 만든다. 제목은 앞머리 title → "
                    + "맨 앞 `# 제목` 줄 → 파일 이름 순. 한 번에 20개·파일당 1MB·UTF-8. 하나라도 거부되면 아무것도 만들지 "
                    + "않는다. 상대 경로 이미지는 해석하지 않고 warnings로 알린다. "
                    + "zip 하나를 올리면 폴더 구조를 문서 트리로 만든다(index.md가 있는 폴더는 그 문서, 없으면 폴더). "
                    + "상대 경로로 가리킨 이미지·파일은 그 문서의 첨부로 올리고 링크를 첨부 URL로, .md끼리의 상대 링크는 "
                    + "새 문서 링크로 바꾼다. zip slip·심볼릭 링크·암호화 항목은 거부, 항목 수·풀린 크기에 상한이 있다.")
    @PostMapping(value = "/api/wiki/spaces/{spaceId}/import/markdown", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MarkdownImportService.Result importMarkdown(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "스페이스 ID") @PathVariable Long spaceId,
            @Parameter(description = "부모 페이지 ID. 비우면 스페이스 루트") @RequestParam(required = false) Long parentId,
            @Parameter(description = "draft 또는 published. 비우면 published") @RequestParam(required = false) String status,
            @Parameter(description = "가져올 .md 파일들(multipart/form-data, 이름 files)")
            @RequestParam("files") List<MultipartFile> files) {
        return service.importFiles(userId(jwt), spaceId, parentId,
                status == null || status.isBlank() ? PageStatus.PUBLISHED : PageStatus.from(status), files);
    }
}
