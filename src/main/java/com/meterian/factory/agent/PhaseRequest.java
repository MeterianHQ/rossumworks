package com.meterian.factory.agent;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** One run of claude: the session, the folder, the prompt, and the allowed tools of the definition of the issue. */
public record PhaseRequest(int issueNumber, UUID sessionId, boolean newSession, Path workingDirectory, String prompt,
                           List<String> allowedTools) {

    public PhaseRequest {
        allowedTools = List.copyOf(allowedTools);
    }
}
