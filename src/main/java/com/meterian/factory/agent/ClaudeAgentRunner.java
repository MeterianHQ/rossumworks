package com.meterian.factory.agent;

import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.process.ProcessGroup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

public final class ClaudeAgentRunner implements AgentRunner {

    private static final System.Logger LOGGER = System.getLogger(ClaudeAgentRunner.class.getName());
    private static final String STOPPED_TEXT = "The phase was stopped.";
    private static final String STOPPING_TEXT = "The factory is stopping.";
    private static final String NO_API_KEY = "none";
    private static final String UNKNOWN_SOURCE_MESSAGE = "The session did not report its credential source.";

    private final ClaudeSettings settings;
    private final Clock clock;
    private final Map<String, String> parentEnvironment;
    private final IntConsumer afterRegistration;
    private final Path claudeHome;
    private final Map<Integer, Run> runs = new ConcurrentHashMap<>();
    private volatile boolean refusesNewPhases;

    public ClaudeAgentRunner(ClaudeSettings settings, Clock clock, Path claudeHome) {
        this(settings, clock, claudeHome, System.getenv(), issueNumber -> { });
    }

    ClaudeAgentRunner(ClaudeSettings settings, Clock clock, Path claudeHome, Map<String, String> parentEnvironment,
            IntConsumer afterRegistration) {
        this.claudeHome = claudeHome;
        this.settings = settings;
        this.clock = clock;
        this.parentEnvironment = parentEnvironment;
        this.afterRegistration = afterRegistration;
    }

    @Override
    public SessionReport runPhase(PhaseRequest request) throws AgentException, ApiKeyDetectedException {
        Run run = new Run();
        if (runs.putIfAbsent(request.issueNumber(), run) != null) {
            throw new AgentException("A phase already runs for the issue " + request.issueNumber() + ".");
        }
        try {
            // The check after the registration: a stopPhase after refuseNewPhases always sees this run.
            if (refusesNewPhases) {
                throw new PhaseRefusedException(STOPPING_TEXT);
            }
            afterRegistration.accept(request.issueNumber());
            run.attach(startProcess(request));
            return readSession(request, run);
        } finally {
            runs.remove(request.issueNumber());
            run.end();
        }
    }

    @Override
    public void stopPhase(int issueNumber) {
        Run run = runs.get(issueNumber);
        if (run != null) {
            run.requestStop();
        }
    }

    @Override
    public boolean hasSavedSession(UUID sessionId, Path worktree) {
        return Files.isRegularFile(ClaudeSessionFile.locate(claudeHome, sessionId, worktree));
    }

    @Override
    public void refuseNewPhases() {
        refusesNewPhases = true;
    }

    private Process startProcess(PhaseRequest request) throws AgentException {
        List<String> command = new ArrayList<>(List.of("setsid", "--wait"));
        command.addAll(ClaudeCommand.arguments(settings, request));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(request.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(ClaudeCommand.environment(parentEnvironment));
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        try {
            Process process = builder.start();
            logStandardError(process, request.issueNumber());
            writePrompt(process, request);
            return process;
        } catch (IOException exception) {
            throw new AgentException("The claude process did not start: " + exception.getMessage(), exception);
        }
    }

    private void writePrompt(Process process, PhaseRequest request) {
        Thread.ofVirtual().start(() -> {
            try (OutputStream input = process.getOutputStream()) {
                input.write(request.prompt().getBytes(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "claude #" + request.issueNumber() + ": the prompt was not written: " + exception.getMessage());
            }
        });
    }

    private SessionReport readSession(PhaseRequest request, Run run)
            throws AgentException, ApiKeyDetectedException {
        StreamEventParser parser = new StreamEventParser(clock);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(run.process().getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parser.accept(line);
                verifyNoApiKey(parser, run);
            }
            return buildReport(run, parser);
        } catch (IOException exception) {
            if (run.isStopRequested()) {
                return new SessionReport(false, STOPPED_TEXT, Optional.empty(), Optional.empty(), List.of(), true);
            }
            throw new AgentException("The claude output cannot be read: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentException("The claude run was interrupted.", exception);
        }
    }

    private void verifyNoApiKey(StreamEventParser parser, Run run) throws ApiKeyDetectedException {
        Optional<String> source = parser.apiKeySource();
        if (source.isPresent() && !NO_API_KEY.equals(source.get())) {
            run.end();
            throw new ApiKeyDetectedException("The session uses the credential source: " + source.get());
        }
    }

    private SessionReport buildReport(Run run, StreamEventParser parser)
            throws InterruptedException, ApiKeyDetectedException {
        int exitCode = run.process().waitFor();
        SessionReport report = parser.report();
        if (report.success() && report.apiKeySource().isEmpty()) {
            throw new ApiKeyDetectedException(UNKNOWN_SOURCE_MESSAGE);
        }
        if (run.isStopRequested() || (report.success() && exitCode != 0)) {
            return new SessionReport(false, report.resultText(), report.apiKeySource(),
                    report.contextUsage(), report.usageWindows(), run.isStopRequested());
        }
        return report;
    }

    private void logStandardError(Process process, int issueNumber) {
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                reader.lines().forEach(line -> LOGGER.log(System.Logger.Level.INFO, "claude #{0}: {1}", issueNumber, line));
            } catch (IOException exception) {
                LOGGER.log(System.Logger.Level.WARNING, "The claude error output cannot be read.", exception);
            }
        });
    }

    private static final class Run {

        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile Process process;
        private volatile boolean stopRequested;

        synchronized void attach(Process startedProcess) {
            process = startedProcess;
            startedProcess.onExit().thenRun(this::end);
            if (stopRequested) {
                end();
            }
        }

        synchronized void requestStop() {
            stopRequested = true;
            if (process != null) {
                end();
            }
        }

        Process process() {
            return process;
        }

        boolean isStopRequested() {
            return stopRequested;
        }

        void end() {
            if (process == null || !ended.compareAndSet(false, true)) {
                return;
            }
            ProcessGroup.terminate(process.pid());
        }
    }
}
