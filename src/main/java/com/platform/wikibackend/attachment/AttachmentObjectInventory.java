package com.platform.wikibackend.attachment;

import java.util.function.Predicate;

/**
 * 첨부 버킷의 객체 목록 — 고아 객체 드라이런 리포트 전용(읽기만).
 *
 * <p>S3 저장소가 켜졌을 때만 빈이 있다({@link S3AttachmentStorage}). 로컬 디스크 저장소는 대상이 아니다.
 */
public interface AttachmentObjectInventory {

    /** 목록을 읽는 버킷 이름 — 행의 {@code storage_bucket}과 같은 것만 대조한다. */
    String bucket();

    /**
     * 버킷의 객체를 <b>키 오름차순</b>으로(S3 ListObjectsV2 순서) 하나씩 넘긴다.
     * {@code sink}가 false를 돌려주면 거기서 멈춘다 — 상한·시간 예산은 호출부가 정한다.
     */
    void forEachObject(Predicate<ListedObject> sink);
}
