package com.rnd.bridge.task;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Bridge doesn't author its own schema for bridge.structured.tasks - it copies Inbox's existing
 * structured-task-event.proto, byte-for-byte, along with everything it imports (see
 * src/main/proto/README.md). Protobuf's wire format is positional by field number, so a drifted
 * copy wouldn't throw a parse error - it would silently decode a field into whatever the drifted
 * copy says that number means. This test is what stands between "someone hand-edited one copy"
 * and that failure mode.
 */
class StructuredTaskProtoDriftTest {

    private static final Path BRIDGE_PROTO_DIR = Path.of("src/main/proto");
    private static final Path INBOX_PROTO_DIR = Path.of("../inbox/src/main/proto");

    @ParameterizedTest
    @ValueSource(strings = {
            "structured-task-event.proto",
            "task-created-event.proto",
            "task-creation-requested-event.proto"
    })
    void bridgeCopyMatchesInboxOriginal_byteForByte(String fileName) throws IOException {
        Path inboxFile = INBOX_PROTO_DIR.resolve(fileName);
        Path bridgeFile = BRIDGE_PROTO_DIR.resolve(fileName);

        if (!Files.exists(inboxFile)) {
            fail("Inbox's original is missing at " + inboxFile.toAbsolutePath()
                    + " - cannot verify Bridge's copy of " + fileName + " is still correct. "
                    + "Did teams/inbox/src/main/proto/" + fileName + " move or get renamed? "
                    + "(teams/inbox must be checked out as a sibling of teams/bridge for this test to run.)");
        }
        if (!Files.isReadable(inboxFile)) {
            fail("Inbox's original at " + inboxFile.toAbsolutePath()
                    + " exists but isn't readable - cannot verify Bridge's copy of " + fileName
                    + " is still correct.");
        }
        if (!Files.exists(bridgeFile)) {
            fail("Bridge's own copy is missing at " + bridgeFile.toAbsolutePath()
                    + " - expected a byte-identical copy of teams/inbox/src/main/proto/" + fileName);
        }

        byte[] inboxBytes = Files.readAllBytes(inboxFile);
        byte[] bridgeBytes = Files.readAllBytes(bridgeFile);

        assertArrayEquals(inboxBytes, bridgeBytes,
                fileName + " has drifted from Inbox's original (teams/inbox/src/main/proto/" + fileName
                        + "). Bridge never edits this file directly - re-copy it byte-for-byte from "
                        + "Inbox's copy, don't hand-edit the difference away.");
    }
}
