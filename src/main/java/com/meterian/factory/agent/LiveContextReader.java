package com.meterian.factory.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the context size of a live session from its local Claude Code file.
 * The reader only reads. It does not follow a symbolic link and does not use the network.
 */
public final class LiveContextReader {

    static final int TAIL_BYTES = 256 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path claudeHome;

    public LiveContextReader(Path claudeHome) {
        this.claudeHome = claudeHome;
    }

    public Optional<Long> readTokensInUse(UUID sessionId, Path worktree) {
        Path sessionFile = ClaudeSessionFile.locate(claudeHome, sessionId, worktree);
        return readTail(sessionFile).flatMap(LiveContextReader::findTokensInLastAssistantLine);
    }

    private Optional<String> readTail(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file, LinkOption.NOFOLLOW_LINKS)) {
            long start = Math.max(0, channel.size() - TAIL_BYTES);
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) (channel.size() - start));
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                continue;
            }
            return Optional.of(new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Long> findTokensInLastAssistantLine(String tail) {
        String[] lines = tail.split("\n");
        for (int index = lines.length - 1; index >= 0; index--) {
            Optional<Long> tokens = sumTokens(lines[index]);
            if (tokens.isPresent()) {
                return tokens;
            }
        }
        return Optional.empty();
    }

    private static Optional<Long> sumTokens(String line) {
        try {
            JsonNode event = MAPPER.readTree(line);
            if (event == null || !"assistant".equals(event.path("type").asText())) {
                return Optional.empty();
            }
            JsonNode usage = event.path("message").path("usage");
            if (!usage.isObject()) {
                return Optional.empty();
            }
            return Optional.of(usage.path("input_tokens").asLong()
                    + usage.path("cache_read_input_tokens").asLong()
                    + usage.path("cache_creation_input_tokens").asLong());
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }
}
