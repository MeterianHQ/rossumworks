package com.meterian.factory.usage;

import com.meterian.factory.agent.ClaudeCommand;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.agent.StreamEventParser;
import com.meterian.factory.agent.UsageWindow;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Reads the usage of the subscription with one very short claude session, so that the page shows the usage before
 * the first phase. The session has no tools, no MCP servers, and no saved session. Fails closed: a session that
 * does not report the subscription as its credential source, that fails, or that does not end in time gives no
 * usage.
 */
public final class UsageProbe {

    /** The prompt of the session. The answer does not matter: only the usage events count. */
    public static final String PROMPT = "Reply with the word OK.";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private static final System.Logger LOGGER = System.getLogger(UsageProbe.class.getName());
    private static final String NO_API_KEY = "none";
    private static final List<String> ARGUMENTS = List.of("-p", "--output-format", "stream-json", "--verbose",
            "--no-session-persistence", "--tools", "", "--strict-mcp-config");

    private final String claudeCommand;
    private final Map<String, String> parentEnvironment;
    private final Clock clock;
    private final Duration timeout;

    public UsageProbe(String claudeCommand, Map<String, String> parentEnvironment, Clock clock, Duration timeout) {
        this.claudeCommand = claudeCommand;
        this.parentEnvironment = parentEnvironment;
        this.clock = clock;
        this.timeout = timeout;
    }

    /** Records the usage windows in the tracker, in a background thread, so that the start of the worker does not wait. */
    public Thread startRecording(UsageTracker tracker) {
        return Thread.ofVirtual().name("usage-probe").start(() -> tracker.record(readUsageWindows()));
    }

    /** Returns the usage windows of the session, or an empty list when the probe fails. */
    public List<UsageWindow> readUsageWindows() {
        Path workingDirectory = null;
        try {
            workingDirectory = Files.createTempDirectory("factory-usage-probe");
            return runSession(workingDirectory);
        } catch (IOException exception) {
            return refuse("The usage probe did not run: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return refuse("The usage probe was interrupted.");
        } finally {
            deleteQuietly(workingDirectory);
        }
    }

    private List<UsageWindow> runSession(Path workingDirectory) throws IOException, InterruptedException {
        Process process = startProcess(workingDirectory);
        writePrompt(process);
        CompletableFuture<Optional<SessionReport>> session = CompletableFuture.supplyAsync(
                () -> readSession(process), runnable -> Thread.ofVirtual().start(runnable));
        try {
            Optional<SessionReport> report = session.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            boolean ended = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return report.filter(found -> ended && process.exitValue() == 0)
                    .map(this::acceptReport)
                    .orElseGet(() -> refuse("The usage probe session did not end with success."));
        } catch (TimeoutException exception) {
            return refuse("The usage probe session did not end in " + timeout.toSeconds() + " s.");
        } catch (ExecutionException exception) {
            return refuse("The usage probe output cannot be read: " + exception.getCause().getMessage());
        } finally {
            stop(process);
        }
    }

    private Process startProcess(Path workingDirectory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(Stream.concat(Stream.of(claudeCommand), ARGUMENTS.stream()).toList());
        builder.directory(workingDirectory.toFile());
        builder.environment().clear();
        builder.environment().putAll(ClaudeCommand.environment(parentEnvironment));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    /** A session that does not read its input closes the pipe: this is not an error of the probe. */
    private void writePrompt(Process process) {
        try (OutputStream input = process.getOutputStream()) {
            input.write(PROMPT.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.DEBUG, "The usage probe prompt was not written: " + exception.getMessage());
        }
    }

    /** Stops at once when the session reports a credential source that is not the subscription. */
    private Optional<SessionReport> readSession(Process process) {
        StreamEventParser parser = new StreamEventParser(clock);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parser.accept(line);
                if (parser.apiKeySource().filter(source -> !NO_API_KEY.equals(source)).isPresent()) {
                    return Optional.of(parser.report());
                }
            }
            return Optional.of(parser.report());
        } catch (IOException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private List<UsageWindow> acceptReport(SessionReport report) {
        if (!report.apiKeySource().equals(Optional.of(NO_API_KEY))) {
            return refuse("The usage probe session did not use the subscription. Its credential source: "
                    + report.apiKeySource().orElse("unknown") + ".");
        }
        if (!report.success()) {
            return refuse("The usage probe session did not end with success.");
        }
        LOGGER.log(System.Logger.Level.INFO, "The usage probe read " + report.usageWindows().size()
                + " usage windows.");
        return report.usageWindows();
    }

    private static List<UsageWindow> refuse(String message) {
        LOGGER.log(System.Logger.Level.WARNING, message + " The page shows the usage after the first phase.");
        return List.of();
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.DEBUG, "The usage probe folder stays: " + directory);
        }
    }
}
