package com.platform.wikibackend.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 리비전 본문 압축(V16).
 *
 * 짧은 본문은 gzip 헤더 때문에 오히려 커진다 — 임계값 아래는 그냥 평문으로 둔다.
 * 임계값을 바꿔도 읽기는 안전하다: 저장된 행이 어느 쪽인지 보고 푼다.
 */
public final class RevisionContent {

    /** 이 길이 미만은 압축해도 이득이 없다(gzip 헤더·트레일러만 18바이트). */
    static final int COMPRESS_THRESHOLD = 512;

    private RevisionContent() {
    }

    public static boolean shouldCompress(String content) {
        return content != null && content.length() >= COMPRESS_THRESHOLD;
    }

    public static byte[] compress(String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("리비전 본문 압축 실패", e);
        }
        return out.toByteArray();
    }

    public static String decompress(byte[] compressed) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("리비전 본문 복원 실패", e);
        }
    }
}
