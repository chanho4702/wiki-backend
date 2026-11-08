package com.platform.wikibackend.admin;

import com.platform.wikibackend.attachment.AttachmentObjectInventory;
import com.platform.wikibackend.attachment.ListedObject;

import java.time.Instant;
import java.util.TreeMap;
import java.util.function.Predicate;

/** 테스트용 버킷 — S3처럼 키 오름차순으로 흘린다. */
class InMemoryObjectInventory implements AttachmentObjectInventory {

    private final String bucket;
    private final TreeMap<String, ListedObject> objects = new TreeMap<>();

    InMemoryObjectInventory(String bucket) {
        this.bucket = bucket;
    }

    void put(String key, long size, Instant lastModified) {
        objects.put(key, new ListedObject(key, size, lastModified));
    }

    void clear() {
        objects.clear();
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public void forEachObject(Predicate<ListedObject> sink) {
        for (ListedObject o : objects.values()) {
            if (!sink.test(o)) return;
        }
    }
}
