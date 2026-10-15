package com.rnd.bridge.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Bridge doesn't author its own schema for bridge.structured.tasks - it copies Inbox's existing
 * structured-task-event.proto, byte-for-byte (see src/main/proto/README.md). Protobuf's wire
 * format is positional by field number, so a drifted copy wouldn't throw a parse error - it
 * would silently decode a field into whatever the drifted copy says that number means. This test
 * is what stands between "someone hand-edited the copy" and that failure mode.
 * <p>
 * Temporary until structured-task-event.proto moves to shared/proto (#567/#618); then delete this
 * test and the copy. task-created-event.proto and task-creation-requested-event.proto (what this
 * test used to also check) no longer have a Bridge-local copy to drift - both teams now build them
 * from shared/proto directly (pom.xml's compile-shared-proto execution).
 */
class StructuredTaskProtoDriftTest {

    private static final Path BRIDGE_PROTO_DIR = Path.of("src/main/proto");
    private static final Path INBOX_PROTO_DIR = Path.of("../inbox/src/main/proto");
    private static final String FILE_NAME = "structured-task-event.proto";

    @Test
    void bridgeCopyMatchesInboxOriginal_byteForByte() throws IOException {
        Path inboxFile = INBOX_PROTO_DIR.resolve(FILE_NAME);
        Path bridgeFile = BRIDGE_PROTO_DIR.resolve(FILE_NAME);

        if (!Files.exists(inboxFile)) {
            fail("Inbox's original is missing at " + inboxFile.toAbsolutePath()
                    + " - cannot verify Bridge's copy of " + FILE_NAME + " is still correct. "
                    + "Did teams/inbox/src/main/proto/" + FILE_NAME + " move or get renamed? "
                    + "(teams/inbox must be checked out as a sibling of teams/bridge for this test to run.)");
        }
        if (!Files.isReadable(inboxFile)) {
            fail("Inbox's original at " + inboxFile.toAbsolutePath()
                    + " exists but isn't readable - cannot verify Bridge's copy of " + FILE_NAME
                    + " is still correct.");
        }
        if (!Files.exists(bridgeFile)) {
            fail("Bridge's own copy is missing at " + bridgeFile.toAbsolutePath()
                    + " - expected a byte-identical copy of teams/inbox/src/main/proto/" + FILE_NAME);
        }

        byte[] inboxBytes = Files.readAllBytes(inboxFile);
        byte[] bridgeBytes = Files.readAllBytes(bridgeFile);

        assertArrayEquals(inboxBytes, bridgeBytes,
                FILE_NAME + " has drifted from Inbox's original (teams/inbox/src/main/proto/" + FILE_NAME
                        + "). Bridge never edits this file directly - re-copy it byte-for-byte from "
                        + "Inbox's copy, don't hand-edit the difference away.");
    }
}
