package com.platform.wikibackend.mdimport;

import com.platform.common.error.BadRequestException;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.page.PageService;
import com.platform.wikibackend.page.dto.PageCreateRequest;
import com.platform.wikibackend.page.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 파일 가져오기 — 사용자가 올린 .md 파일 하나하나를 선택한 부모 아래 새 문서로 만든다.
 *
 * 왜 위키 안인가: "이관 로직은 위키 밖 migration-service"라는 확정 결정은 외부 시스템(컨플·노션)의 API를 읽고
 * 주체·링크·첨부를 대조하는 엔진에 대한 것이다. 이것은 그런 엔진이 아니라 "본문을 붙여 넣어 새 문서를 만들기"의
 * 파일 버전이다 — 원격 호출·주체 매핑·원본 메타가 없고, 저장은 {@link PageService#create} 한 길만 탄다.
 * 그래서 권한(스페이스 EDIT·부모 편집 가능)·형제 순번·리비전·작업/백링크 파생·자동 구독·게시 알림·색인 이벤트가
 * 손으로 만든 문서와 똑같다. 초안으로 가져오면 알림·검색에서 빠지는 것도 같은 경로라 그대로다.
 *
 * 신뢰 모델: 본문은 저장 형식(마크다운) 그대로 들어간다. raw HTML을 걷어내지 않는 이유는 보기 렌더러·에디터·PDF/Word
 * 내보내기가 모두 raw HTML을 렌더하지 않기 때문이다(글자로 보인다) — 같은 내용을 편집기에 붙여 넣어 저장하는 것과
 * 위험이 같다. 상대 경로 이미지(./img.png 등)는 함께 올라온 파일이 없어 해석할 수 없다 — 링크는 그대로 두고
 * 파일별 경고로 알린다.
 *
 * 한 번의 가져오기는 한 트랜잭션이다 — 파일 하나라도 거부되면 아무 문서도 만들지 않는다(반쯤 들어간 상태를 남기지 않는다).
 */
@Service
@RequiredArgsConstructor
public class MarkdownImportService {

    static final int MAX_FILES = 20;
    static final long MAX_FILE_BYTES = 1024 * 1024;
    static final int MAX_TITLE = 255;

    private static final Set<String> EXTENSIONS = Set.of(".md", ".markdown");
    /**
     * 브라우저가 .md에 붙이는 MIME은 제각각이다(윈도는 빈 값이나 octet-stream이 흔하다). 확장자와 아래 본문
     * 검사(UTF-8·NUL 없음)가 실제 판정이고, MIME은 "명백히 다른 형식"만 거른다.
     */
    private static final Set<String> CONTENT_TYPES = Set.of(
            "", "text/markdown", "text/x-markdown", "text/plain", "application/octet-stream");

    private static final Pattern HEADING_TITLE = Pattern.compile("^#\\s+(.+?)(?:\\s+#+)?\\s*$");
    private static final Pattern FRONT_MATTER_TITLE =
            Pattern.compile("(?m)^title\\s*:\\s*(?:\"(.*)\"|'(.*)'|(.*?))\\s*$");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(\\s*<?([^)\\s>]+)");

    private final PageService pageService;

    public record Warning(String file, String message) {
    }

    public record Result(List<PageResponse> pages, List<Warning> warnings) {
    }

    record Parsed(String title, String body, int relativeImages) {
    }

    @Transactional
    public Result importFiles(long userId, long spaceId, Long parentId, PageStatus status, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("가져올 Markdown 파일을 선택하세요");
        }
        if (files.size() > MAX_FILES) {
            throw new BadRequestException("한 번에 " + MAX_FILES + "개까지 가져올 수 있습니다");
        }
        // 모두 검사한 뒤에 쓴다 — 마지막 파일이 거부돼도 앞 파일의 알림·이벤트가 새지 않게
        List<String> names = new ArrayList<>();
        List<Parsed> parsed = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = displayName(file);
            names.add(name);
            parsed.add(parse(name, read(name, file)));
        }

        PageStatus effectiveStatus = status == null ? PageStatus.PUBLISHED : status;
        List<PageResponse> created = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            Parsed p = parsed.get(i);
            created.add(pageService.create(userId, new PageCreateRequest(
                    spaceId, parentId, p.title(), p.body(), PageType.PAGE, effectiveStatus)));
            if (p.relativeImages() > 0) {
                warnings.add(new Warning(names.get(i), "상대 경로 이미지 " + p.relativeImages()
                        + "개는 가져오지 못했습니다. 문서에 이미지를 직접 첨부하세요."));
            }
        }
        return new Result(created, warnings);
    }

    private static String displayName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) return "이름 없는 파일";
        // 브라우저·OS에 따라 경로가 붙어 온다 — 파일 이름만
        String name = original.replace('\\', '/');
        return name.substring(name.lastIndexOf('/') + 1);
    }

    private static String read(String name, MultipartFile file) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            throw new BadRequestException("Markdown 파일(.md, .markdown)만 가져올 수 있습니다: " + name);
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        if (semicolon >= 0) type = type.substring(0, semicolon);
        if (!CONTENT_TYPES.contains(type.trim())) {
            throw new BadRequestException("Markdown 파일이 아닙니다: " + name);
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BadRequestException("파일 하나는 1MB까지 가져올 수 있습니다: " + name);
        }
        byte[] bytes;
        try {
            bytes = file.getInputStream().readNBytes((int) MAX_FILE_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new BadRequestException("파일 하나는 1MB까지 가져올 수 있습니다: " + name);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new BadRequestException("UTF-8 텍스트 파일이 아닙니다: " + name);
        }
        if (text.indexOf('\0') >= 0) {
            throw new BadRequestException("텍스트 파일이 아닙니다: " + name);
        }
        return text;
    }

    /** 제목: 앞머리(front matter) title → 맨 앞 `# 제목` 줄 → 파일 이름 순. 제목으로 쓴 줄은 본문에서 뺀다. */
    static Parsed parse(String fileName, String raw) {
        String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
        text = text.replace("\r\n", "\n").replace('\r', '\n');

        String title = null;
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0 && (end + 4 == text.length() || text.charAt(end + 4) == '\n')) {
                String front = text.substring(4, end);
                Matcher m = FRONT_MATTER_TITLE.matcher(front);
                if (m.find()) {
                    title = firstNonNull(m.group(1), m.group(2), m.group(3));
                }
                text = text.substring(Math.min(text.length(), end + 5));
            }
        }
        if (title == null || title.isBlank()) {
            String stripped = text.stripLeading();
            int lineEnd = stripped.indexOf('\n');
            String firstLine = lineEnd < 0 ? stripped : stripped.substring(0, lineEnd);
            Matcher m = HEADING_TITLE.matcher(firstLine);
            if (m.matches()) {
                title = m.group(1);
                text = lineEnd < 0 ? "" : stripped.substring(lineEnd + 1);
            }
        }
        if (title == null || title.isBlank()) {
            title = fileName.replaceFirst("(?i)\\.(md|markdown)$", "");
        }
        title = title.strip();
        if (title.isBlank()) title = "제목 없음";
        if (title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE);

        String body = text.replaceFirst("^\\n+", "");
        return new Parsed(title, body, countRelativeImages(body));
    }

    private static int countRelativeImages(String body) {
        int count = 0;
        Matcher m = IMAGE.matcher(body);
        while (m.find()) {
            String url = m.group(1).toLowerCase(Locale.ROOT);
            boolean resolvable = url.startsWith("http://") || url.startsWith("https://")
                    || url.startsWith("/") || url.startsWith("data:");
            if (!resolvable) count++;
        }
        return count;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) return v;
        }
        return null;
    }
}
