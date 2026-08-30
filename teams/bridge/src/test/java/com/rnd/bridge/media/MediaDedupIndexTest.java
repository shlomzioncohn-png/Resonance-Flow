package com.rnd.bridge.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.raw.model.RawSourceType;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MediaDedupIndexTest {

    private final MinioClient minioClient = mock(MinioClient.class);
    private final MediaDedupIndex dedupIndex = new MediaDedupIndex(minioClient, new ObjectMapper(), "bridge-raw");

    @Test
    void loadReturnsEmptySetWhenIndexObjectDoesNotExistYet() throws Exception {
        ErrorResponse notFound = new ErrorResponse(
                "NoSuchKey", "not found", "bridge-raw", "_dedup/MEETING/demo-tenant.json", null, null, null);
        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new ErrorResponseException(notFound, null, "req-1"));

        Set<String> hashes = dedupIndex.load(RawSourceType.MEETING, "demo-tenant");

        assertThat(hashes).isEmpty();
    }

    @Test
    void loadParsesAnExistingIndex() throws Exception {
        String json = "[\"sha256:aaa\",\"sha256:bbb\"]";
        GetObjectResponse response = new GetObjectResponse(
                new Headers.Builder().build(), "bridge-raw", "us-east-1", "_dedup/MEETING/demo-tenant.json",
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(response);

        Set<String> hashes = dedupIndex.load(RawSourceType.MEETING, "demo-tenant");

        assertThat(hashes).containsExactlyInAnyOrder("sha256:aaa", "sha256:bbb");
    }

    @Test
    void saveWritesToTheExpectedKeyAndRoundTripsTheHashSet() throws Exception {
        dedupIndex.save(RawSourceType.SCANNED_DOC, "demo-tenant", Set.of("sha256:ccc"));

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        PutObjectArgs written = captor.getValue();

        assertThat(written.bucket()).isEqualTo("bridge-raw");
        assertThat(written.object()).isEqualTo("_dedup/SCANNED_DOC/demo-tenant.json");
        String[] roundTripped = new ObjectMapper().readValue(written.stream(), String[].class);
        assertThat(roundTripped).containsExactly("sha256:ccc");
    }
}
