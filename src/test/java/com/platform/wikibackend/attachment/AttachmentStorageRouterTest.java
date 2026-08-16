package com.platform.wikibackend.attachment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentStorageRouterTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 설정한_쓰기_backend가_비활성화면_시작을_거부한다() {
        AttachmentStorage local = storage(StorageBackend.LOCAL);

        assertThatThrownBy(() -> new AttachmentStorageRouter(List.of(local), "s3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("활성화되지 않은 첨부 저장소");
    }

    @Test
    void DB_커밋_전에는_객체를_삭제하지_않는다() {
        AttachmentStorage local = storage(StorageBackend.LOCAL);
        AttachmentStorageRouter router = new AttachmentStorageRouter(List.of(local), "local");
        TransactionSynchronizationManager.initSynchronization();

        router.deleteAfterCommit(StorageBackend.LOCAL, null, "key", null);
        verify(local, never()).delete(null, "key", null);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(local).delete(null, "key", null);
    }

    @Test
    void 업로드_객체는_DB_롤백시에만_삭제한다() {
        AttachmentStorage local = storage(StorageBackend.LOCAL);
        AttachmentStorageRouter router = new AttachmentStorageRouter(List.of(local), "local");
        TransactionSynchronizationManager.initSynchronization();

        router.deleteAfterRollback(new StoredObject(StorageBackend.LOCAL, null, "key", null));
        TransactionSynchronization synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
        synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        verify(local, never()).delete(null, "key", null);

        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(local).delete(null, "key", null);
    }

    private AttachmentStorage storage(StorageBackend backend) {
        AttachmentStorage storage = mock(AttachmentStorage.class);
        when(storage.backend()).thenReturn(backend);
        when(storage.delete(null, "key", null)).thenReturn(true);
        return storage;
    }
}
