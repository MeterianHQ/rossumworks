package com.meterian.factory.agent;

import java.nio.file.Path;
import java.util.UUID;

/** The place of the file where Claude Code saves a session. */
final class ClaudeSessionFile {

    private ClaudeSessionFile() {
    }

    static Path locate(Path claudeHome, UUID sessionId, Path worktree) {
        String folderName = worktree.toAbsolutePath().toString().replaceAll("[^A-Za-z0-9]", "-");
        return claudeHome.resolve("projects").resolve(folderName).resolve(sessionId + ".jsonl");
    }
}
