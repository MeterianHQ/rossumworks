package com.meterian.factory.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** Reads a JSON result file of an agent. A missing, large, or invalid file gives an empty result. */
final class ResultFileReader {

    static final long MAXIMUM_BYTES = 1_000_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResultFileReader() {
    }

    static Optional<JsonNode> readObject(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            byte[] content = readLimited(file);
            if (content.length > MAXIMUM_BYTES) {
                return Optional.empty();
            }
            JsonNode node = MAPPER.readTree(content);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static byte[] readLimited(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return stream.readNBytes((int) MAXIMUM_BYTES + 1);
        }
    }
}
