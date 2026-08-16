package com.platform.wikibackend.attachment;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3AttachmentStorageTest {

    @Test
    void 저장시_MIME과_버전_ID를_보존한다() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId("v-7").build());
        S3AttachmentStorage storage = new S3AttachmentStorage(client, "wiki-test");

        StoredObject result = storage.store(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, "image/png");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("wiki-test");
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(result.backend()).isEqualTo(StorageBackend.S3);
        assertThat(result.bucket()).isEqualTo("wiki-test");
        assertThat(result.key()).hasSize(36);
        assertThat(result.version()).isEqualTo("v-7");
    }

    @Test
    void 조회와_삭제에_저장된_버전을_전달한다() throws Exception {
        S3Client client = mock(S3Client.class);
        GetObjectResponse response = GetObjectResponse.builder().contentLength(3L).build();
        ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
                response, AbortableInputStream.create(new ByteArrayInputStream(new byte[]{1, 2, 3})));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
        S3AttachmentStorage storage = new S3AttachmentStorage(client, "wiki-test");

        assertThat(storage.open("wiki-old", "object-key", "v-7").getContentAsByteArray()).containsExactly(1, 2, 3);
        assertThat(storage.delete("wiki-old", "object-key", "v-7")).isTrue();

        ArgumentCaptor<GetObjectRequest> get = ArgumentCaptor.forClass(GetObjectRequest.class);
        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).getObject(get.capture());
        verify(client).deleteObject(delete.capture());
        assertThat(get.getValue().bucket()).isEqualTo("wiki-old");
        assertThat(get.getValue().versionId()).isEqualTo("v-7");
        assertThat(delete.getValue().versionId()).isEqualTo("v-7");
        assertThat(delete.getValue().bucket()).isEqualTo("wiki-old");
    }
}
