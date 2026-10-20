package com.rnd.chain.events;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * chain-task-updated-event.proto is a self-contained copy of task-updated-event.proto plus the
 * enums it imports from the shared creation schema. Wire format is positional, so a drifted field
 * number or enum value would silently decode into the wrong field.
 */
class ChainTaskUpdatedProtoDriftTest {

    private static final Path SHARED_PROTO_DIR = Path.of("../../shared/proto");
    private static final List<String> ENUMS =
            List.of("SourceType", "TaskTier", "TaskRoleContext", "TaskStatus", "ExtractionProvenance");

    @Test
    void messageMatchesOriginal() throws IOException {
        assertEquals(
                definition("task-updated-event.proto", "message", "TaskUpdatedEvent"),
                definition("chain-task-updated-event.proto", "message", "TaskUpdatedEvent"));
    }

    @Test
    void enumsMatchOriginal() throws IOException {
        for (String name : ENUMS) {
            assertEquals(
                    definition("task-created-event.proto", "enum", name),
                    definition("chain-task-updated-event.proto", "enum", name),
                    name + " has drifted");
        }
    }

    private static String definition(String file, String kind, String name) throws IOException {
        String source = Files.readString(SHARED_PROTO_DIR.resolve(file));
        Matcher m = Pattern.compile(kind + "\\s+" + name + "\\s*\\{(.*?)\\n\\}", Pattern.DOTALL).matcher(source);
        if (!m.find()) {
            throw new AssertionError(kind + " " + name + " not found in " + file);
        }
        return m.group(1).replaceAll("//[^\\n]*", "").replaceAll("\\s+", " ").trim();
    }
}
