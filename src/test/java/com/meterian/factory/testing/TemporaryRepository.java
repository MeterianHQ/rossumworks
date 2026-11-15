package com.meterian.factory.testing;

import com.meterian.factory.git.GitException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Makes local git repositories for tests. It never uses the global git identity. */
public final class TemporaryRepository {

    private TemporaryRepository() {
    }

    private static void createParentDirectory(Path directory) {
        try {
            Files.createDirectories(directory.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static Path createBare(Path directory) {
        run(directory.getParent(), "init", "-q", "--bare", "-b", "main", directory.toString());
        return directory;
    }

    /** Clones the bare repository and pushes one commit to the branch main. */
    public static Path createClone(Path bareRepository, Path directory) {
        createParentDirectory(directory);
        run(directory.getParent(), "clone", "-q", bareRepository.toString(), directory.toString());
        run(directory, "config", "user.name", "Factory Test");
        run(directory, "config", "user.email", "factory-test@example.com");
        run(directory, "checkout", "-q", "-B", "main");
        commitFile(directory, "a.txt", "first\n", "First commit");
        run(directory, "push", "-q", "origin", "main");
        return directory;
    }

    public static void commitFile(Path repository, String fileName, String content, String message) {
        try {
            Path file = repository.resolve(fileName);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        run(repository, "add", fileName);
        run(repository, "commit", "-q", "-m", message);
    }

    public static String run(Path directory, String... arguments) {
        return runWithEnvironment(Map.of(), directory, arguments);
    }

    /** Runs git with the isolation of the global configuration, then adds the given entries. */
    public static String runWithEnvironment(Map<String, String> environment, Path directory, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true);
            builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().putAll(environment);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                throw new GitException(String.join(" ", command) + " failed: " + output);
            }
            return output.trim();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
