package com.platform.wikibackend.mdimport;

import com.platform.common.error.BadRequestException;
import com.platform.wikibackend.attachment.AttachmentReferences;
import com.platform.wikibackend.attachment.AttachmentService;
import com.platform.wikibackend.attachment.dto.AttachmentResponse;
import com.platform.wikibackend.domain.PageStatus;
import com.platform.wikibackend.domain.PageType;
import com.platform.wikibackend.page.PageService;
import com.platform.wikibackend.page.dto.PageCreateRequest;
import com.platform.wikibackend.page.dto.PageResponse;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Markdown zip 가져오기 — 묶음 하나를 문서 트리로 옮긴다. {@link MarkdownImportService}의 트랜잭션 안에서만 부른다.
 *
 * 구조: 폴더는 그 안에 `index.md`가 있으면 그 문서가 되고, 없으면 폴더(Page.type=folder)가 된다. Markdown이 하나도
 * 없는 폴더(이미지 폴더 등)는 문서를 만들지 않는다. 형제는 이름순.
 *
 * 링크: 상대 경로로 가리킨 이미지·파일은 그 링크를 쓴 문서의 첨부로 올리고(일반 업로드와 같은 저장 경로) 첨부 URL로
 * 바꿔 쓴다. 다른 .md·폴더를 가리킨 상대 링크는 새 문서 링크(`/spaces/{s}/pages/{id}`)로 바꾼다. 못 찾은 상대
 * 링크는 그대로 두고 경고로 센다. 아무도 가리키지 않은 파일은 올리지 않는다.
 *
 * 안전: zip slip(`..`·절대 경로·드라이브 문자) 거부, 심볼릭 링크·암호화 항목 거부, 항목 수·풀린 크기 합계·항목당
 * 크기에 상한(설정값). 크기는 선언값을 믿지 않고 실제로 풀면서 센다. 하나라도 어기면 아무것도 만들지 않는다 —
 * 첨부 저장소 객체는 DB 롤백 때 함께 지워진다(AttachmentStorageRouter.deleteAfterRollback).
 */
@Component
class MarkdownZipImporter {

    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
    private static final Pattern DRIVE = Pattern.compile("^[a-zA-Z]:");

    private final PageService pageService;
    private final AttachmentService attachments;
    private final int maxEntries;
    private final long maxTotalBytes;
    private final long maxEntryBytes;
    private final int maxPages;

    MarkdownZipImporter(PageService pageService, AttachmentService attachments,
                        @Value("${platform.wiki.markdown-import.zip.max-entries:1000}") int maxEntries,
                        @Value("${platform.wiki.markdown-import.zip.max-total-size:200MB}") DataSize maxTotalSize,
                        @Value("${platform.wiki.markdown-import.zip.max-entry-size:20MB}") DataSize maxEntrySize,
                        @Value("${platform.wiki.markdown-import.zip.max-pages:300}") int maxPages) {
        this.pageService = pageService;
        this.attachments = attachments;
        this.maxEntries = maxEntries;
        this.maxTotalBytes = maxTotalSize.toBytes();
        this.maxEntryBytes = maxEntrySize.toBytes();
        this.maxPages = maxPages;
    }

    /** 묶음 안의 파일 하나 — 경로는 `/`로 이은 정규화(NFC) 상대 경로. Markdown이면 본문을 들고 있다. */
    private record FileEntry(String path, ZipArchiveEntry entry, long size, String text) {
        boolean markdown() {
            return text != null;
        }

        String name() {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    MarkdownImportService.Result importZip(long userId, long spaceId, Long parentId, PageStatus status,
                                           MultipartFile upload, String zipName) {
        byte[] bytes;
        try {
            bytes = upload.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (ZipFile zip = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(bytes))
                .setCharset(StandardCharsets.UTF_8)
                .get()) {
            Map<String, FileEntry> files = scan(zip);
            return new Run(userId, spaceId, status, zip, files).execute(parentId);
        } catch (IOException e) {
            throw new BadRequestException("zip 파일을 읽을 수 없습니다: " + zipName);
        }
    }

    // ── 1. 검사 — 쓰기 전에 전부 ─────────────────────────────

    private Map<String, FileEntry> scan(ZipFile zip) throws IOException {
        Map<String, FileEntry> files = new LinkedHashMap<>();
        Set<String> lowerPaths = new HashSet<>();
        int count = 0;
        long total = 0;
        for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
            if (++count > maxEntries) {
                throw new BadRequestException("zip 항목이 너무 많습니다(최대 " + maxEntries + "개)");
            }
            String raw = entry.getName();
            if (entry.isUnixSymlink()) {
                throw new BadRequestException("심볼릭 링크는 가져올 수 없습니다: " + raw);
            }
            String path = normalizeEntryName(raw);
            if (path == null || entry.isDirectory()) continue; // 건너뛸 항목·디렉터리(구조는 파일 경로에서 나온다)
            if (!zip.canReadEntryData(entry)) {
                throw new BadRequestException("암호화되었거나 지원하지 않는 압축 방식입니다: " + raw);
            }
            boolean markdown = MarkdownImportService.isMarkdownName(path);
            long limit = markdown ? MarkdownImportService.MAX_FILE_BYTES : maxEntryBytes;
            long size;
            String text = null;
            try (InputStream in = zip.getInputStream(entry)) {
                if (markdown) {
                    byte[] content = readLimited(in, limit, raw);
                    size = content.length;
                    text = MarkdownImportService.decodeText(path, content);
                } else {
                    size = countLimited(in, limit, raw);
                }
            }
            total += size;
            if (total > maxTotalBytes) {
                throw new BadRequestException("zip을 풀면 너무 큽니다(최대 " + DataSize.ofBytes(maxTotalBytes).toMegabytes() + "MB)");
            }
            if (!lowerPaths.add(path.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("같은 경로의 항목이 두 번 있습니다: " + raw);
            }
            files.put(path, new FileEntry(path, entry, size, text));
        }
        if (files.values().stream().noneMatch(FileEntry::markdown)) {
            throw new BadRequestException("zip 안에 Markdown 파일(.md, .markdown)이 없습니다");
        }
        return files;
    }

    /**
     * 항목 이름을 안전한 상대 경로로. zip slip이면 거부(예외), 건너뛸 항목(macOS 메타데이터·숨김 파일)이면 null.
     * NFC로 맞춘다 — macOS가 만든 zip은 한글 이름이 NFD라 본문의 링크(NFC)와 글자가 달라진다.
     */
    static String normalizeEntryName(String raw) {
        if (raw.indexOf('\0') >= 0) throw new BadRequestException("이름이 잘못된 항목이 있습니다");
        String name = raw.replace('\\', '/');
        if (name.startsWith("/") || DRIVE.matcher(name).find()) {
            throw new BadRequestException("절대 경로 항목은 가져올 수 없습니다: " + raw);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : name.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                throw new BadRequestException("상위 경로(..)를 가리키는 항목은 가져올 수 없습니다: " + raw);
            }
            if (segment.startsWith(".") || segment.equals("__MACOSX") || segment.equals("Thumbs.db")) {
                return null; // 숨김·OS 메타데이터(.DS_Store, ._*, .git/ …)
            }
            segments.add(Normalizer.normalize(segment, Normalizer.Form.NFC));
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }

    private static byte[] readLimited(InputStream in, long limit, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long read = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
            read += n;
            if (read > limit) throw tooLarge(name, limit);
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static long countLimited(InputStream in, long limit, String name) throws IOException {
        byte[] buffer = new byte[8192];
        long read = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
            read += n;
            if (read > limit) throw tooLarge(name, limit);
        }
        return read;
    }

    private static BadRequestException tooLarge(String name, long limit) {
        return new BadRequestException("zip 안의 파일이 너무 큽니다(최대 " + Math.max(1, limit / (1024 * 1024))
                + "MB): " + name);
    }

    // ── 2. 쓰기 — 한 번의 가져오기 ──────────────────────────

    private final class Run {
        private final long userId;
        private final long spaceId;
        private final PageStatus status;
        private final ZipFile zip;
        private final Map<String, FileEntry> files;
        private final Map<String, String> byLowerPath = new HashMap<>();
        /** Markdown이 들어 있는 폴더(조상 포함). */
        private final Set<String> directories = new TreeSet<>();
        /** .md 경로·폴더 경로 → 만든 문서 id. */
        private final Map<String, Long> pageIds = new HashMap<>();
        private final List<Long> created = new ArrayList<>();
        private final Set<String> referenced = new HashSet<>();
        private final List<MarkdownImportService.Warning> warnings = new ArrayList<>();

        Run(long userId, long spaceId, PageStatus status, ZipFile zip, Map<String, FileEntry> files) {
            this.userId = userId;
            this.spaceId = spaceId;
            this.status = status;
            this.zip = zip;
            this.files = files;
            for (FileEntry f : files.values()) {
                byLowerPath.put(f.path().toLowerCase(Locale.ROOT), f.path());
                if (!f.markdown()) continue;
                for (String dir = parentOf(f.path()); !dir.isEmpty(); dir = parentOf(dir)) {
                    directories.add(dir);
                }
            }
        }

        MarkdownImportService.Result execute(Long parentId) {
            long pageCount = files.values().stream().filter(FileEntry::markdown).count()
                    + directories.stream().filter(d -> indexOf(d) == null).count();
            if (pageCount > maxPages) {
                throw new BadRequestException("zip으로 한 번에 만들 수 있는 문서는 " + maxPages + "개까지입니다(이 묶음: "
                        + pageCount + "개)");
            }
            createChildren("", parentId);
            for (FileEntry f : files.values()) {
                if (f.markdown()) finalizeBody(f);
            }
            long unreferenced = files.values().stream()
                    .filter(f -> !f.markdown() && !referenced.contains(f.path()))
                    .count();
            if (unreferenced > 0) {
                warnings.add(new MarkdownImportService.Warning("zip", "어느 문서도 가리키지 않은 파일 " + unreferenced
                        + "개는 가져오지 않았습니다."));
            }
            List<PageResponse> pages = created.stream()
                    .map(id -> PageResponse.from(pageService.getOwned(id)))
                    .toList();
            return new MarkdownImportService.Result(pages, warnings);
        }

        /** 폴더 dir의 직계 항목(하위 폴더·.md, index 제외)을 이름순으로 parent 아래에 만든다. */
        private void createChildren(String dir, Long parent) {
            FileEntry index = dir.isEmpty() ? null : indexOf(dir);
            List<String> children = new ArrayList<>();
            for (String sub : directories) {
                if (parentOf(sub).equals(dir)) children.add(sub);
            }
            for (FileEntry f : files.values()) {
                if (f.markdown() && parentOf(f.path()).equals(dir) && f != index) children.add(f.path());
            }
            children.sort(Comparator.comparing((String p) -> sortKey(p)).thenComparing(p -> p));
            for (String child : children) {
                if (directories.contains(child)) {
                    long id = createDirectory(child, parent);
                    createChildren(child, id);
                } else {
                    FileEntry f = files.get(child);
                    pageIds.put(child, createDocument(f, parent, null));
                }
            }
        }

        private long createDirectory(String dir, Long parent) {
            String name = dir.substring(dir.lastIndexOf('/') + 1);
            FileEntry index = indexOf(dir);
            long id;
            if (index != null) {
                id = createDocument(index, parent, name);
                pageIds.put(index.path(), id);
            } else {
                id = create(name, "", PageType.FOLDER, parent);
            }
            pageIds.put(dir, id);
            return id;
        }

        /** fallbackTitle: index.md처럼 파일 이름이 제목으로 맞지 않을 때 대신 쓸 이름(폴더 이름). */
        private long createDocument(FileEntry f, Long parent, String fallbackTitle) {
            MarkdownImportService.Parsed parsed = MarkdownImportService.parse(f.name(), f.text());
            String title = parsed.title();
            String stem = f.name().replaceFirst("(?i)\\.(md|markdown)$", "");
            if (fallbackTitle != null && title.equals(stem)) title = fallbackTitle;
            return create(title, parsed.body(), PageType.PAGE, parent);
        }

        private long create(String title, String body, PageType type, Long parent) {
            String safeTitle = title.length() > MarkdownImportService.MAX_TITLE
                    ? title.substring(0, MarkdownImportService.MAX_TITLE) : title;
            PageResponse page = pageService.create(userId,
                    new PageCreateRequest(spaceId, parent, safeTitle, body, type, status));
            created.add(page.id());
            return page.id();
        }

        /** 본문의 상대 링크를 첨부·새 문서 링크로 바꿔 v1을 마무리한다. */
        private void finalizeBody(FileEntry f) {
            long pageId = pageIds.get(f.path());
            String body = pageService.getOwned(pageId).getContent();
            String dir = parentOf(f.path());
            // 같은 파일을 여러 번(이미지와 링크로 함께) 가리켜도 이 문서의 첨부는 하나다
            Map<String, AttachmentResponse> attachedByPath = new HashMap<>();
            Set<String> usedNames = new HashSet<>();
            int[] unresolved = {0};
            String rewritten = MarkdownLinkRewriter.rewrite(body, (destination, image) -> {
                if (!isRelative(destination)) return Optional.empty();
                Optional<Target> target = resolveRelative(dir, destination);
                if (target.isEmpty()) {
                    unresolved[0]++;
                    return Optional.empty();
                }
                String path = target.get().path();
                Long linkedPage = pageIds.get(path);
                if (linkedPage != null) {
                    return Optional.of("/spaces/" + spaceId + "/pages/" + linkedPage + target.get().fragment());
                }
                FileEntry file = files.get(path);
                if (file == null || file.markdown()) {
                    unresolved[0]++;
                    return Optional.empty();
                }
                referenced.add(path);
                AttachmentResponse attachment = attachedByPath.get(path);
                if (attachment == null) {
                    attachment = attachments.uploadImported(userId, pageId, uniqueName(file.name(), usedNames),
                            file.size(), () -> zip.getInputStream(file.entry()));
                    attachedByPath.put(path, attachment);
                }
                return Optional.of(urlFor(attachment.id(), attachment.contentType(), image));
            });
            pageService.finalizeCreatedContent(pageId, rewritten);
            if (unresolved[0] > 0) {
                warnings.add(new MarkdownImportService.Warning(f.path(), "찾지 못한 상대 링크 " + unresolved[0]
                        + "개는 그대로 두었습니다."));
            }
        }

        /**
         * 이미지로 쓰였고 실제로 그릴 수 있는 형식이면 inline, 아니면 내려받기 주소 — inline은 안전한 형식만 연다
         * (형식은 확장자가 아니라 저장할 때 바이트로 판별한 값이다).
         */
        private static String urlFor(long attachmentId, String contentType, boolean image) {
            return image && contentType != null && contentType.startsWith("image/")
                    ? AttachmentReferences.inlineUrl(attachmentId)
                    : AttachmentReferences.downloadUrl(attachmentId);
        }

        /** 상대 목적지를 묶음 안의 경로로. 묶음 밖·못 찾음은 빈 값. 폴더를 가리키면(`./guide/`) 그 폴더의 문서로 간다. */
        private Optional<Target> resolveRelative(String dir, String destination) {
            String path = destination.trim();
            String fragment = "";
            int hash = path.indexOf('#');
            if (hash >= 0) {
                fragment = path.substring(hash);
                path = path.substring(0, hash);
            }
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            try {
                path = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // 잘못된 % 인코딩 — 적힌 그대로 찾아본다
            }
            path = Normalizer.normalize(path.replace('\\', '/'), Normalizer.Form.NFC);
            List<String> segments = new ArrayList<>();
            if (!dir.isEmpty()) segments.addAll(List.of(dir.split("/")));
            for (String segment : path.split("/")) {
                if (segment.isEmpty() || segment.equals(".")) continue;
                if (segment.equals("..")) {
                    if (segments.isEmpty()) return Optional.empty(); // 묶음 밖
                    segments.remove(segments.size() - 1);
                } else {
                    segments.add(segment);
                }
            }
            String resolved = String.join("/", segments);
            if (pageIds.containsKey(resolved) || files.containsKey(resolved)) {
                return Optional.of(new Target(resolved, fragment));
            }
            String caseFolded = byLowerPath.get(resolved.toLowerCase(Locale.ROOT));
            if (caseFolded != null) return Optional.of(new Target(caseFolded, fragment));
            for (String d : directories) {
                if (d.equalsIgnoreCase(resolved)) return Optional.of(new Target(d, fragment));
            }
            return Optional.empty();
        }

        private FileEntry indexOf(String dir) {
            FileEntry exact = files.get(dir + "/index.md");
            if (exact != null) return exact;
            for (FileEntry f : files.values()) {
                if (f.markdown() && parentOf(f.path()).equals(dir)
                        && f.name().toLowerCase(Locale.ROOT).matches("index\\.(md|markdown)")) {
                    return f;
                }
            }
            return null;
        }
    }

    private record Target(String path, String fragment) {
    }

    private static boolean isRelative(String destination) {
        String d = destination.trim();
        return !d.isEmpty() && !d.startsWith("#") && !d.startsWith("/") && !SCHEME.matcher(d).find();
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String sortKey(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.replaceFirst("(?i)\\.(md|markdown)$", "").toLowerCase(Locale.ROOT);
    }

    private static String uniqueName(String name, Set<String> used) {
        if (used.add(name.toLowerCase(Locale.ROOT))) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String candidate = stem + " (" + i + ")" + ext;
            if (used.add(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
    }
}
