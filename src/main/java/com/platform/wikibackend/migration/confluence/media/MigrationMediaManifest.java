package com.platform.wikibackend.migration.confluence.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.platform.wikibackend.attachment.StorageBackend;
import com.platform.wikibackend.attachment.StoredObject;

import java.util.List;

/**
 * MEDIA_COPY가 받아 둔 첨부 바이트의 좌표 목록(`migration_payload(MEDIA_MANIFEST)`).
 *
 * 이 목록이 존재하는 이유는 **단계 사이에 파일을 두 번 옮기지 않기 위해서**다. MEDIA_COPY는 대상
 * 페이지가 없는 시점에 돌기 때문에 첨부 레코드를 만들 수 없고, 그렇다고 RESOLVE에서 다시 받으면
 * 재실행마다 원본을 통째로 다시 긁는다. 그래서 바이트는 여기서 한 번만 받아 저장소에 두고,
 * RESOLVE는 이 좌표를 첨부 레코드에 그대로 옮겨 적는다.
 *
 * 재실행 멱등의 근거이기도 하다 — 같은 파일명·같은 원본 버전이 이미 여기 있으면 다시 받지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MigrationMediaManifest(int version, List<Entry> files) {

    /** 지금 쓰는 형식 번호. 바꿀 일이 생기면 올리고 읽는 쪽에서 갈라 본다. */
    public static final int VERSION = 1;

    public MigrationMediaManifest {
        files = files == null ? List.of() : List.copyOf(files);
    }

    public static MigrationMediaManifest of(List<Entry> files) {
        return new MigrationMediaManifest(VERSION, files);
    }

    public static MigrationMediaManifest empty() {
        return new MigrationMediaManifest(VERSION, List.of());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String filename, String contentType, long size, String checksum,
                        StorageBackend storageBackend, String storageBucket, String storageKey,
                        String storageVersion, int sourceVersion) {

        /** 저장소 좌표. 첨부 레코드가 이 값을 그대로 들고 같은 객체를 가리킨다. */
        public StoredObject storedObject() {
            return new StoredObject(storageBackend, storageBucket, storageKey, storageVersion);
        }

        /** 같은 파일의 같은 원본 버전인가 — 재다운로드를 건너뛸 판정 키다. */
        public String stagingKey() {
            return filename + "@" + sourceVersion;
        }
    }
}
