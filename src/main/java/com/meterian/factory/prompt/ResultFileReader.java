package com.meterian.factory.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
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
            if (!Files.isRegularFile(file) || Files.size(file) > MAXIMUM_BYTES) {
                return Optional.empty();
            }
            JsonNode node = MAPPER.readTree(Files.readAllBytes(file));
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }
}
