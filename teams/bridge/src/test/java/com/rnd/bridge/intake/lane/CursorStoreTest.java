package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.storage.ObjectStorageClient;
import com.rnd.bridge.storage.ObjectStorageClient.PutResult;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CursorStoreTest {

    private final ObjectStorageClient storageClient = mock(ObjectStorageClient.class);
    private final CursorStore cursorStore =
            new CursorStore(storageClient, new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void loadReturnsFullCursorWhenNothingSavedYet() {
        when(storageClient.getObject("_cursors/EMAIL/kamatech.json")).thenReturn(Optional.empty());

        ReadCursor cursor = cursorStore.load(RawSourceType.EMAIL, "kamatech");

        assertThat(cursor).isEqualTo(ReadCursor.full());
    }

    @Test
    void saveThenLoadRoundTripsTheCursor() {
        ReadCursor saved = new ReadCursor(Instant.parse("2026-08-30T12:00:00Z"));
        when(storageClient.putObject(any(), any(), any(), any())).thenReturn(new PutResult("application/json", 0L));
        cursorStore.save(RawSourceType.EMAIL, "kamatech", saved);

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(storageClient).putObject(
                eq("_cursors/EMAIL/kamatech.json"), bodyCaptor.capture(), eq("application/json"), any());
        when(storageClient.getObject("_cursors/EMAIL/kamatech.json")).thenReturn(Optional.of(bodyCaptor.getValue()));

        ReadCursor loaded = cursorStore.load(RawSourceType.EMAIL, "kamatech");

        assertThat(loaded).isEqualTo(saved);
    }
}
