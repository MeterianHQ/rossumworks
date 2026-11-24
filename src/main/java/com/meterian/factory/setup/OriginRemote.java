package com.meterian.factory.setup;

import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.process.ChildEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Reads the GitHub repository of a folder from its remote origin. */
final class OriginRemote {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private OriginRemote() {
    }

    /** A folder that is not a git folder, or a remote that is not on GitHub, gives no repository. */
    static Optional<RepositoryName> readRepository(Path folder) {
        try {
            ProcessBuilder builder = new ProcessBuilder("git", "remote", "get-url", "origin")
                    .directory(folder.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            ChildEnvironment.removeAnthropicCredentials(builder.environment());
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 ? GitHubRemoteUrl.parse(output) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
