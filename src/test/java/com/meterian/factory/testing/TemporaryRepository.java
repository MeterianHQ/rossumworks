package com.meterian.factory.testing;

import com.meterian.factory.git.GitException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Makes local git repositories for tests. It never uses the global git identity. */
public final class TemporaryRepository {

    private TemporaryRepository() {
    }

    public static Path createBare(Path directory) {
        run(directory.getParent(), "init", "-q", "--bare", "-b", "main", directory.toString());
        return directory;
    }

    /** Clones the bare repository and pushes one commit to the branch main. */
    public static Path createClone(Path bareRepository, Path directory) {
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
            Files.writeString(repository.resolve(fileName), content);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        run(repository, "add", fileName);
        run(repository, "commit", "-q", "-m", message);
    }

    public static String run(Path directory, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
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
