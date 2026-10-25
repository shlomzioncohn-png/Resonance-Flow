package com.rnd.bridge.intake.parser.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.StartPageToken;
import com.google.api.services.drive.model.User;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.config.DriveImportProperties;
import com.rnd.bridge.intake.model.DriveFileState;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.DriveFileStateReconciler;
import com.rnd.bridge.raw.model.ChangeType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Covers RealDriveSourceReader.read(ReadCursor)'s two modes (full files.list baseline when
 * cursor.token() is null, changes.list delta otherwise - see class javadoc), the native-
 * Google-type (export) vs. plain-binary (get) content-fetch branch, and the headRevisionId ->
 * revision / trashed -> DELETE_REVIEW mapping via a mocked DriveFileStateReconciler (the
 * reconciler's own first-seen/retry/updated algorithm is covered by DriveFileStateReconcilerTest,
 * not re-tested here).
 * Drive/Drive.Files/Drive.Changes/the per-call request types are mocked (Mockito.RETURNS_SELF
 * for the fluent set* chain) because .execute()/.executeMediaAsInputStream() would otherwise
 * make a real HTTP call; File/FileList/ChangeList/User are used as plain POJOs since they're
 * just GenericJson data classes with no I/O.
 */
class RealDriveSourceReaderTest {

    private static final DriveImportProperties PROPERTIES = new DriveImportProperties("kamatech", null);

    private final Drive drive = mock(Drive.class);
    private final Drive.Files files = mock(Drive.Files.class);
    private final Drive.Changes changes = mock(Drive.Changes.class);
    private final DriveFileStateReconciler fileStateReconciler = mock(DriveFileStateReconciler.class);

    private RealDriveSourceReader reader() {
        return reader(PROPERTIES);
    }

    private RealDriveSourceReader reader(DriveImportProperties properties) {
        when(drive.files()).thenReturn(files);
        when(drive.changes()).thenReturn(changes);
        when(fileStateReconciler.resolve(anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> new DriveFileState(
                        inv.getArgument(1), inv.getArgument(0), "corr-" + inv.getArgument(1), inv.getArgument(3),
                        inv.getArgument(4), 0, false));
        return new RealDriveSourceReader(null, properties, fileStateReconciler, drive);
    }

    private Drive.Files.List mockListRequest() {
        return mock(Drive.Files.List.class, Mockito.RETURNS_SELF);
    }

    private void stubStartPageToken(String token) throws IOException {
        Drive.Changes.GetStartPageToken request = mock(Drive.Changes.GetStartPageToken.class);
        when(changes.getStartPageToken()).thenReturn(request);
        when(request.execute()).thenReturn(new StartPageToken().setStartPageToken(token));
    }

    private File file(String id, String mimeType) {
        return new File()
                .setId(id)
                .setName("name-" + id)
                .setDescription("description")
                .setMimeType(mimeType)
                .setModifiedTime(new DateTime(1_700_000_000_000L))
                .setOwners(List.of(new User().setEmailAddress("malki@kamatech.org")))
                .setHeadRevisionId("rev-1");
    }

    // --- Baseline (full files.list) mode: cursor.token() == null -----------------------------

    @Test
    void binaryFileIsFetchedViaGetAndItsRealBytesLandOnTheRecord() throws IOException {
        Drive.Files.List request = mockListRequest();
        when(files.list()).thenReturn(request);
        when(request.execute()).thenReturn(new FileList().setFiles(List.of(file("f1", "application/pdf"))));
        stubStartPageToken("start-1");

        Drive.Files.Get getRequest = mock(Drive.Files.Get.class);
        when(files.get("f1")).thenReturn(getRequest);
        byte[] expectedBytes = "real pdf bytes".getBytes(StandardCharsets.UTF_8);
        when(getRequest.executeMediaAsInputStream()).thenReturn(new ByteArrayInputStream(expectedBytes));

        DriveReadResult result = reader().read(ReadCursor.full());

        assertThat(result.records()).hasSize(1);
        SourceRecord record = result.records().get(0);
        assertThat(record.rawContent()).isEqualTo(expectedBytes);
        assertThat(record.mimeType()).isEqualTo("application/pdf");
        assertThat(record.sender()).isEqualTo("malki@kamatech.org");
        assertThat(result.nextToken()).isEqualTo("start-1");
    }

    @Test
    void nativeGoogleFileIsExportedAsTextPlainRatherThanFetchedViaGet() throws IOException {
        Drive.Files.List request = mockListRequest();
        when(files.list()).thenReturn(request);
        when(request.execute())
                .thenReturn(new FileList().setFiles(List.of(file("doc1", "application/vnd.google-apps.document"))));
        stubStartPageToken("start-1");

        Drive.Files.Export exportRequest = mock(Drive.Files.Export.class);
        when(files.export("doc1", "text/plain")).thenReturn(exportRequest);
        byte[] expectedBytes = "exported text".getBytes(StandardCharsets.UTF_8);
        when(exportRequest.executeMediaAsInputStream()).thenReturn(new ByteArrayInputStream(expectedBytes));

        DriveReadResult result = reader().read(ReadCursor.full());

        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).rawContent()).isEqualTo(expectedBytes);
        verify(files, Mockito.never()).get("doc1");
    }

    @Test
    void paginationFollowsNextPageTokenAcrossMultiplePages() throws IOException {
        Drive.Files.List page1Request = mockListRequest();
        Drive.Files.List page2Request = mockListRequest();
        when(files.list()).thenReturn(page1Request, page2Request);

        when(page1Request.execute())
                .thenReturn(new FileList().setFiles(List.of(file("f1", "application/pdf"))).setNextPageToken("page-2"));
        when(page2Request.execute())
                .thenReturn(new FileList().setFiles(List.of(file("f2", "application/pdf"))));
        stubStartPageToken("start-1");

        Drive.Files.Get getRequest = mock(Drive.Files.Get.class);
        when(files.get(anyString())).thenReturn(getRequest);
        when(getRequest.executeMediaAsInputStream())
                .thenReturn(new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));

        DriveReadResult result = reader().read(ReadCursor.full());

        assertThat(result.records()).hasSize(2);
        verify(page2Request).setPageToken("page-2");
    }

    @Test
    void rootFolderIdScopesTheListQueryWhenConfigured() throws IOException {
        DriveImportProperties scoped = new DriveImportProperties("kamatech", "folder-123");
        Drive.Files.List request = mockListRequest();
        when(files.list()).thenReturn(request);
        when(request.execute()).thenReturn(new FileList().setFiles(List.of()));
        stubStartPageToken("start-1");

        reader(scoped).read(ReadCursor.full());

        verify(request).setQ("'folder-123' in parents");
    }

    @Test
    void firstRunMintsAStartPageTokenAlongsideTheBaselineScan() throws IOException {
        Drive.Files.List request = mockListRequest();
        when(files.list()).thenReturn(request);
        when(request.execute()).thenReturn(new FileList().setFiles(List.of()));
        stubStartPageToken("fresh-start-token");

        DriveReadResult result = reader().read(ReadCursor.full());

        assertThat(result.nextToken()).isEqualTo("fresh-start-token");
    }

    // --- Incremental (changes.list) mode: cursor.token() != null ------------------------------

    @Test
    void incrementalReadUsesChangesListNotFilesList() throws IOException {
        Drive.Changes.List changesRequest = mock(Drive.Changes.List.class, Mockito.RETURNS_SELF);
        when(changes.list("page-token-1")).thenReturn(changesRequest);
        Change change = new Change().setFileId("f1").setRemoved(false).setFile(file("f1", "application/pdf"));
        when(changesRequest.execute()).thenReturn(new ChangeList().setChanges(List.of(change)));

        Drive.Files.Get getRequest = mock(Drive.Files.Get.class);
        when(files.get("f1")).thenReturn(getRequest);
        when(getRequest.executeMediaAsInputStream())
                .thenReturn(new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));

        DriveReadResult result = reader().read(new ReadCursor(null, "page-token-1"));

        assertThat(result.records()).hasSize(1);
        verify(files, Mockito.never()).list();
    }

    @Test
    void incrementalReadCapturesNewStartPageTokenOnlyFromTheLastPage() throws IOException {
        Drive.Changes.List page1 = mock(Drive.Changes.List.class, Mockito.RETURNS_SELF);
        Drive.Changes.List page2 = mock(Drive.Changes.List.class, Mockito.RETURNS_SELF);
        // Each page is requested via a fresh changes().list(pageToken) call with that page's own
        // token (not a single request mutated in place) - stub by call order, not by argument.
        when(changes.list(anyString())).thenReturn(page1, page2);

        when(page1.execute()).thenReturn(new ChangeList().setChanges(List.of()).setNextPageToken("page-token-2"));
        when(page2.execute()).thenReturn(new ChangeList().setChanges(List.of()).setNewStartPageToken("next-start"));

        DriveReadResult result = reader().read(new ReadCursor(null, "page-token-1"));

        assertThat(result.nextToken()).isEqualTo("next-start");
        verify(changes).list("page-token-2");
    }

    @Test
    void removedChangesAreSkippedEntirely() throws IOException {
        Drive.Changes.List changesRequest = mock(Drive.Changes.List.class, Mockito.RETURNS_SELF);
        when(changes.list("page-token-1")).thenReturn(changesRequest);
        Change removed = new Change().setFileId("gone").setRemoved(true);
        when(changesRequest.execute()).thenReturn(new ChangeList().setChanges(List.of(removed)));

        DriveReadResult result = reader().read(new ReadCursor(null, "page-token-1"));

        assertThat(result.records()).isEmpty();
    }

    @Test
    void trashedFileBecomesDeleteReviewWithoutFetchingContent() throws IOException {
        Drive.Changes.List changesRequest = mock(Drive.Changes.List.class, Mockito.RETURNS_SELF);
        when(changes.list("page-token-1")).thenReturn(changesRequest);
        File trashedFile = file("f1", "application/pdf").setTrashed(true);
        Change change = new Change().setFileId("f1").setRemoved(false).setFile(trashedFile);
        when(changesRequest.execute()).thenReturn(new ChangeList().setChanges(List.of(change)));

        DriveReadResult result = reader().read(new ReadCursor(null, "page-token-1"));

        assertThat(result.records()).hasSize(1);
        SourceRecord record = result.records().get(0);
        assertThat(record.changeType()).isEqualTo(ChangeType.DELETE_REVIEW);
        assertThat(record.sourceRef()).isEqualTo("f1");
        verify(files, Mockito.never()).get(anyString());
        verify(files, Mockito.never()).export(anyString(), anyString());
    }

    @Test
    void ordinaryFileCarriesItsFileIdAsSourceRef() throws IOException {
        Drive.Files.List request = mockListRequest();
        when(files.list()).thenReturn(request);
        when(request.execute()).thenReturn(new FileList().setFiles(List.of(file("f1", "application/pdf"))));
        stubStartPageToken("start-1");
        Drive.Files.Get getRequest = mock(Drive.Files.Get.class);
        when(files.get("f1")).thenReturn(getRequest);
        when(getRequest.executeMediaAsInputStream())
                .thenReturn(new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));

        SourceRecord record = reader().read(ReadCursor.full()).records().get(0);

        assertThat(record.sourceRef()).isEqualTo("f1");
    }

    @Test
    void editedFileGetsTheReconciledRevisionFromFileStateReconciler() throws IOException {
        RealDriveSourceReader reader = reader(); // registers the default reconciler stub first
        when(fileStateReconciler.resolve("kamatech", "f1", "application/pdf", "rev-2", null))
                .thenReturn(new DriveFileState("f1", "kamatech", "corr-f1", "rev-2", null, 3, false));
        Drive.Changes.List changesRequest = mock(Drive.Changes.List.class, Mockito.RETURNS_SELF);
        when(changes.list("page-token-1")).thenReturn(changesRequest);
        File editedFile = file("f1", "application/pdf").setHeadRevisionId("rev-2");
        Change change = new Change().setFileId("f1").setRemoved(false).setFile(editedFile);
        when(changesRequest.execute()).thenReturn(new ChangeList().setChanges(List.of(change)));

        Drive.Files.Get getRequest = mock(Drive.Files.Get.class);
        when(files.get("f1")).thenReturn(getRequest);
        when(getRequest.executeMediaAsInputStream())
                .thenReturn(new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));

        DriveReadResult result = reader.read(new ReadCursor(null, "page-token-1"));

        SourceRecord record = result.records().get(0);
        assertThat(record.correlationId()).isEqualTo("corr-f1");
        assertThat(record.revision()).isEqualTo(3);
    }

    // --- 410 (expired startPageToken) fallback ------------------------------------------------

    @Test
    void expiredStartPageTokenFallsBackToAFullBaselineResync() throws IOException {
        Drive.Changes.List changesRequest = mock(Drive.Changes.List.class, Mockito.RETURNS_SELF);
        when(changes.list("expired-token")).thenReturn(changesRequest);
        HttpResponseException.Builder builder = new HttpResponseException.Builder(410, "Gone", new HttpHeaders());
        GoogleJsonResponseException expired = new GoogleJsonResponseException(builder, null);
        when(changesRequest.execute()).thenThrow(expired);

        Drive.Files.List listRequest = mockListRequest();
        when(files.list()).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(new FileList().setFiles(List.of()));
        stubStartPageToken("fresh-start-token");

        DriveReadResult result = reader().read(new ReadCursor(null, "expired-token"));

        assertThat(result.nextToken()).isEqualTo("fresh-start-token");
    }

    // --- Full read -> real DriveFileStateReconciler path (headRevisionId never populated for ---
    // --- native Google files, so version is the native-file change signal - see that class) ---

    private RealDriveSourceReader readerWithRealReconciler() {
        when(drive.files()).thenReturn(files);
        when(drive.changes()).thenReturn(changes);
        var backingStore = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
        var store = new com.rnd.bridge.intake.lane.DriveFileStateStore(
                new com.rnd.bridge.storage.InMemoryObjectStorageClient(backingStore),
                com.rnd.bridge.storage.ControlPlaneStateStores.inMemory(backingStore),
                new com.fasterxml.jackson.databind.ObjectMapper());
        var realReconciler = new DriveFileStateReconciler(store);
        return new RealDriveSourceReader(null, PROPERTIES, realReconciler, drive);
    }

    private File nativeDocFile(String id, long version) {
        return new File()
                .setId(id)
                .setName("doc-" + id)
                .setMimeType("application/vnd.google-apps.document")
                .setModifiedTime(new DateTime(1_700_000_000_000L))
                .setOwners(List.of(new User().setEmailAddress("malki@kamatech.org")))
                .setHeadRevisionId(null)
                .setVersion(version);
    }

    private void stubExport(String fileId, byte[] bytes) throws IOException {
        Drive.Files.Export exportRequest = mock(Drive.Files.Export.class);
        when(files.export(eq(fileId), anyString())).thenReturn(exportRequest);
        when(exportRequest.executeMediaAsInputStream()).thenReturn(new ByteArrayInputStream(bytes));
    }

    @Test
    void nativeGoogleDocEditThroughTheFullReadReconcilePathBumpsRevision() throws IOException {
        RealDriveSourceReader reader = readerWithRealReconciler();
        stubExport("doc1", "v1 text".getBytes(StandardCharsets.UTF_8));

        Drive.Files.List listRequest = mockListRequest();
        when(files.list()).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(new FileList().setFiles(List.of(nativeDocFile("doc1", 1L))));
        stubStartPageToken("start-1");

        SourceRecord baseline = reader.read(ReadCursor.full()).records().get(0);
        assertThat(baseline.revision()).isZero();

        Drive.Changes.List changesRequest = mock(Drive.Changes.List.class, Mockito.RETURNS_SELF);
        when(changes.list("start-1")).thenReturn(changesRequest);
        Change edited = new Change().setFileId("doc1").setRemoved(false).setFile(nativeDocFile("doc1", 2L));
        when(changesRequest.execute()).thenReturn(new ChangeList().setChanges(List.of(edited)));

        SourceRecord afterEdit = reader.read(new ReadCursor(null, "start-1")).records().get(0);

        assertThat(afterEdit.revision()).isEqualTo(1);
        assertThat(afterEdit.correlationId()).isEqualTo(baseline.correlationId());
    }

    @Test
    void binaryFileChangeDetectionThroughTheFullPathIsUnchangedByTheNativeFileFix() throws IOException {
        RealDriveSourceReader reader = readerWithRealReconciler();

        Drive.Files.List listRequest = mockListRequest();
        when(files.list()).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(new FileList().setFiles(List.of(file("f1", "application/pdf"))));
        stubStartPageToken("start-1");
        Drive.Files.Get getRequest = mock(Drive.Files.Get.class);
        when(files.get("f1")).thenReturn(getRequest);
        when(getRequest.executeMediaAsInputStream())
                .thenReturn(new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));

        SourceRecord baseline = reader.read(ReadCursor.full()).records().get(0);
        assertThat(baseline.revision()).isZero();

        Drive.Changes.List changesRequest = mock(Drive.Changes.List.class, Mockito.RETURNS_SELF);
        when(changes.list("start-1")).thenReturn(changesRequest);
        File editedFile = file("f1", "application/pdf").setHeadRevisionId("rev-2");
        Change change = new Change().setFileId("f1").setRemoved(false).setFile(editedFile);
        when(changesRequest.execute()).thenReturn(new ChangeList().setChanges(List.of(change)));
        when(getRequest.executeMediaAsInputStream())
                .thenReturn(new ByteArrayInputStream("bytes2".getBytes(StandardCharsets.UTF_8)));

        SourceRecord afterEdit = reader.read(new ReadCursor(null, "start-1")).records().get(0);

        assertThat(afterEdit.revision()).isEqualTo(1);
        assertThat(afterEdit.correlationId()).isEqualTo(baseline.correlationId());
    }

    @Test
    void nativeGoogleDocUnchangedRetryThroughTheFullPathKeepsTheSameRevision() throws IOException {
        RealDriveSourceReader reader = readerWithRealReconciler();
        stubExport("doc1", "text".getBytes(StandardCharsets.UTF_8));

        Drive.Files.List listRequest = mockListRequest();
        when(files.list()).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(new FileList().setFiles(List.of(nativeDocFile("doc1", 1L))));
        stubStartPageToken("start-1");

        SourceRecord baseline = reader.read(ReadCursor.full()).records().get(0);

        Drive.Changes.List changesRequest = mock(Drive.Changes.List.class, Mockito.RETURNS_SELF);
        when(changes.list("start-1")).thenReturn(changesRequest);
        // Same version as the baseline read - e.g. a retry after a failed publish, nothing
        // actually changed server-side.
        Change retryChange = new Change().setFileId("doc1").setRemoved(false).setFile(nativeDocFile("doc1", 1L));
        when(changesRequest.execute()).thenReturn(new ChangeList().setChanges(List.of(retryChange)));

        SourceRecord retry = reader.read(new ReadCursor(null, "start-1")).records().get(0);

        assertThat(retry.revision()).isEqualTo(baseline.revision());
        assertThat(retry.correlationId()).isEqualTo(baseline.correlationId());
    }
}
