package com.rnd.relay.canonicalize;

import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Canonicalize {

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:public\\s+|weak\\s+)?\"([^\"]+)\"\\s*;", Pattern.MULTILINE);

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: canonicalize <proto-dir> <file.proto>");
            System.exit(2);
        }
        Path protoDir = Path.of(args[0]);
        String schema = Files.readString(protoDir.resolve(args[1]));

        Map<String, String> imports = new LinkedHashMap<>();
        collectImports(protoDir, schema, imports);

        String canonical = new ProtobufSchema(schema, List.of(), imports, null, null).canonicalString();
        System.out.write(canonical.getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }

    private static void collectImports(Path protoDir, String schema, Map<String, String> imports) throws IOException {
        Matcher m = IMPORT.matcher(schema);
        while (m.find()) {
            String name = m.group(1);
            if (name.startsWith("google/protobuf/") || imports.containsKey(name)) {
                continue;
            }
            String text = Files.readString(protoDir.resolve(name));
            imports.put(name, text);
            collectImports(protoDir, text, imports);
        }
    }
}
