package com.meterian.factory;

import com.meterian.factory.Main.StartupException;
import com.meterian.factory.configuration.ProviderName;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** The checks that the commands run and create make before they start: the credential, the programs, the token. */
public final class StartChecks {

    private static final String API_KEY_VARIABLE = "ANTHROPIC_API_KEY";
    private static final String TOKEN_VARIABLE = "FACTORY_GITHUB_TOKEN";
    private static final Duration TOKEN_COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final System.Logger LOGGER = System.getLogger(StartChecks.class.getName());

    private StartChecks() {
    }

    /** A source of the token that can stop the start, for example a command that does not answer. */
    @FunctionalInterface
    public interface TokenSource {
        Optional<String> read() throws StartupException;
    }

    /** The variable ANTHROPIC_API_KEY must agree with the provider of the configuration. */
    public static void verifyCredential(Map<String, String> environment, ProviderName provider)
            throws StartupException {
        boolean hasApiKey = !environment.getOrDefault(API_KEY_VARIABLE, "").isBlank();
        if (provider == ProviderName.SUBSCRIPTION && hasApiKey) {
            throw new StartupException("Remove ANTHROPIC_API_KEY: the provider is subscription. "
                    + "To use the key, set claude.provider to anthropic-api-key.");
        }
        if (provider == ProviderName.ANTHROPIC_API_KEY && !hasApiKey) {
            throw new StartupException("Set ANTHROPIC_API_KEY: the provider is anthropic-api-key. "
                    + "To use the claude login, remove claude.provider.");
        }
    }

    public static void verifyCommandsOnPath(List<String> commands, String path) throws StartupException {
        List<String> missing = findMissingCommands(commands, path);
        if (!missing.isEmpty()) {
            throw new StartupException("The factory needs these commands on the PATH: " + String.join(", ", missing));
        }
    }

    /** A command with a slash is a file path. Another command must be an executable file in a PATH folder. */
    public static List<String> findMissingCommands(List<String> commands, String path) {
        List<String> missing = new ArrayList<>();
        for (String command : commands) {
            if (!isExecutableCommand(command, path)) {
                missing.add(command);
            }
        }
        return missing;
    }

    public static boolean isExecutableCommand(String command, String path) {
        if (command.contains("/")) {
            return Files.isExecutable(Path.of(command));
        }
        return Arrays.stream(path.split(File.pathSeparator))
                .filter(folder -> !folder.isBlank())
                .map(folder -> Path.of(folder).resolve(command))
                .anyMatch(file -> Files.isRegularFile(file) && Files.isExecutable(file));
    }

    public static String readToken(Map<String, String> environment, TokenSource ghToken) throws StartupException {
        String fromEnvironment = environment.getOrDefault(TOKEN_VARIABLE, "");
        if (!fromEnvironment.isBlank()) {
            return fromEnvironment.strip();
        }
        return ghToken.read()
                .filter(token -> !token.isBlank())
                .orElseThrow(() -> new StartupException("Set FACTORY_GITHUB_TOKEN, or log in with gh auth login."));
    }

    public static Optional<String> readTokenFromGh() throws StartupException {
        return readTokenFromCommand(List.of("gh", "auth", "token"), TOKEN_COMMAND_TIMEOUT);
    }

    /**
     * Runs the command and returns its output. A missing command or a failure gives no token. A command that
     * does not end in the timeout stops the start.
     */
    static Optional<String> readTokenFromCommand(List<String> command, Duration timeout) throws StartupException {
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("factory-token", ".out");
            Process process = new ProcessBuilder(command)
                    .redirectOutput(outputFile.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new StartupException("The command " + String.join(" ", command) + " did not answer in "
                        + describeSeconds(timeout) + " seconds. Set FACTORY_GITHUB_TOKEN.");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8).strip();
            return process.exitValue() == 0 ? Optional.of(output) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            deleteQuietly(outputFile);
        }
    }

    private static String describeSeconds(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? Long.toString(millis / 1000) : Double.toString(millis / 1000.0);
    }

    /** The file can hold the token, so it must not stay on the disk. */
    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Cannot delete the temporary file {0}", file);
        }
    }
}
