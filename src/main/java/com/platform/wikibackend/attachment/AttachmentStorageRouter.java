package com.platform.wikibackend.attachment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class AttachmentStorageRouter {

    private final Map<StorageBackend, AttachmentStorage> storages;
    private final StorageBackend writeBackend;

    public AttachmentStorageRouter(List<AttachmentStorage> storages,
                                   @Value("${platform.wiki.storage.write-backend:local}") String writeBackend) {
        EnumMap<StorageBackend, AttachmentStorage> indexed = new EnumMap<>(StorageBackend.class);
        for (AttachmentStorage storage : storages) {
            if (indexed.put(storage.backend(), storage) != null) {
                throw new IllegalStateException("중복 첨부 저장소: " + storage.backend());
            }
        }
        this.storages = Map.copyOf(indexed);
        this.writeBackend = StorageBackend.fromConfig(writeBackend);
        require(this.writeBackend);
    }

    public StoredObject store(InputStream input, long contentLength, String contentType) {
        return require(writeBackend).store(input, contentLength, contentType);
    }

    public Resource open(StorageBackend backend, String bucket, String key, String version) {
        return require(backend).open(bucket, key, version);
    }

    /** 업로드 DB 트랜잭션이 실패하면 먼저 저장한 객체를 치운다. */
    public void deleteAfterRollback(StoredObject object) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteQuietly(object.backend(), object.bucket(), object.key(), object.version());
                }
            }
        });
    }

    /** DB 삭제가 실제 커밋된 뒤에만 객체를 지워 롤백 시 파일 유실을 막는다. */
    public void deleteAfterCommit(StorageBackend backend, String bucket, String key, String version) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteQuietly(backend, bucket, key, version);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(backend, bucket, key, version);
            }
        });
    }

    private AttachmentStorage require(StorageBackend backend) {
        AttachmentStorage storage = storages.get(backend);
        if (storage == null) {
            throw new IllegalStateException("활성화되지 않은 첨부 저장소: " + backend);
        }
        return storage;
    }

    private void deleteQuietly(StorageBackend backend, String bucket, String key, String version) {
        try {
            if (!require(backend).delete(bucket, key, version)) {
                log.warn("첨부 객체 삭제 실패(고아 객체): backend={}, bucket={}, key={}, version={}",
                        backend, bucket, key, version);
            }
        } catch (RuntimeException e) {
            log.warn("첨부 객체 삭제 실패(고아 객체): backend={}, bucket={}, key={}, version={}",
                    backend, bucket, key, version, e);
        }
    }
}
