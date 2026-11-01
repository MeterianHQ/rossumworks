package com.meterian.factory.agent;

import java.nio.file.Path;
import java.util.UUID;

public record PhaseRequest(int issueNumber, UUID sessionId, boolean newSession, Path workingDirectory, String prompt) {
}
