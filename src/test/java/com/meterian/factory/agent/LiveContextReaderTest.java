package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveContextReaderTest {

    private static final Path WORKTREE = Path.of("/home/u/.claude-ai-factory/work/issue-7");
    private static final String FOLDER = "-home-u--claude-ai-factory-work-issue-7";

    @TempDir
    Path claudeHome;

    private final UUID sessionId = UUID.randomUUID();

    @Test
    void readsTheSumOfTheLastAssistantTurn() throws IOException {
        writeSessionFile(
                assistantLine(1, 10, 20) + "\n" + assistantLine(2, 34697, 938) + "\n");

        assertThat(reader().readTokensInUse(sessionId, WORKTREE)).contains(35637L);
    }

    @Test
    void findsTheFolderOfTheWorktree() throws IOException {
        writeSessionFile(assistantLine(1, 2, 3) + "\n");

        assertThat(Files.exists(claudeHome.resolve("projects").resolve(FOLDER).resolve(sessionId + ".jsonl")))
                .isTrue();
        assertThat(reader().readTokensInUse(sessionId, WORKTREE)).contains(6L);
    }

    @Test
    void skipsABrokenLastLine() throws IOException {
        String cutLine = assistantLine(5, 5, 5).substring(0, 30);
        writeSessionFile(assistantLine(1, 2, 3) + "\n" + cutLine);

        assertThat(reader().readTokensInUse(sessionId, WORKTREE)).contains(6L);
    }

    @Test
    void readsTheEndOfALargeFile() throws IOException {
        String userLine = "{\"type\":\"user\",\"message\":{\"content\":\"" + "x".repeat(990) + "\"}}\n";
        writeSessionFile(userLine.repeat(1100) + assistantLine(4, 5, 6) + "\n");

        assertThat(reader().readTokensInUse(sessionId, WORKTREE)).contains(15L);
    }

    @Test
    void aMissingFileGivesNothing() {
        Optional<Long> tokens = reader().readTokensInUse(sessionId, WORKTREE);

        assertThat(tokens).isEmpty();
    }

    @Test
    void aSymbolicLinkGivesNothing() throws IOException {
        Path target = claudeHome.resolve("target.jsonl");
        Files.writeString(target, assistantLine(1, 2, 3) + "\n");
        Path folder = Files.createDirectories(claudeHome.resolve("projects").resolve(FOLDER));
        Files.createSymbolicLink(folder.resolve(sessionId + ".jsonl"), target);

        assertThat(reader().readTokensInUse(sessionId, WORKTREE)).isEmpty();
    }

    private LiveContextReader reader() {
        return new LiveContextReader(claudeHome);
    }

    private void writeSessionFile(String content) throws IOException {
        Path folder = Files.createDirectories(claudeHome.resolve("projects").resolve(FOLDER));
        Files.writeString(folder.resolve(sessionId + ".jsonl"), content, StandardCharsets.UTF_8);
    }

    private static String assistantLine(long input, long cacheRead, long cacheCreation) {
        return "{\"type\":\"assistant\",\"message\":{\"usage\":{\"input_tokens\":" + input
                + ",\"cache_read_input_tokens\":" + cacheRead
                + ",\"cache_creation_input_tokens\":" + cacheCreation + ",\"output_tokens\":9}}}";
    }
}
