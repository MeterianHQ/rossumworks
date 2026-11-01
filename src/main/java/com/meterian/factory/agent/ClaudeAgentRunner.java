package com.meterian.factory.agent;

import com.meterian.factory.configuration.ClaudeSettings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ClaudeAgentRunner implements AgentRunner {

    private static final System.Logger LOGGER = System.getLogger(ClaudeAgentRunner.class.getName());
    private static final String NO_API_KEY = "none";
    private static final String UNKNOWN_SOURCE_MESSAGE = "The session did not report its credential source.";

    private final ClaudeSettings settings;
    private final Clock clock;
    private final Map<Integer, Process> runningProcesses = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> stoppedIssues = new ConcurrentHashMap<>();

    public ClaudeAgentRunner(ClaudeSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public SessionReport runPhase(PhaseRequest request) throws AgentException, ApiKeyDetectedException {
        Process process = startProcess(request);
        runningProcesses.put(request.issueNumber(), process);
        stoppedIssues.remove(request.issueNumber());
        try {
            return readSession(request, process);
        } finally {
            runningProcesses.remove(request.issueNumber());
            stoppedIssues.remove(request.issueNumber());
            endProcessTree(process);
        }
    }

    @Override
    public void stopPhase(int issueNumber) {
        Process process = runningProcesses.get(issueNumber);
        if (process != null) {
            stoppedIssues.put(issueNumber, true);
            endProcessTree(process);
        }
    }

    private Process startProcess(PhaseRequest request) throws AgentException {
        ProcessBuilder builder = new ProcessBuilder(ClaudeCommand.arguments(settings, request));
        builder.directory(request.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(ClaudeCommand.environment(System.getenv()));
        builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        try {
            Process process = builder.start();
            logStandardError(process, request.issueNumber());
            return process;
        } catch (IOException exception) {
            throw new AgentException("The claude process did not start: " + exception.getMessage(), exception);
        }
    }

    private SessionReport readSession(PhaseRequest request, Process process)
            throws AgentException, ApiKeyDetectedException {
        StreamEventParser parser = new StreamEventParser(clock);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parser.accept(line);
                verifyNoApiKey(parser, process);
            }
            return buildReport(request, process, parser);
        } catch (IOException exception) {
            throw new AgentException("The claude output cannot be read: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentException("The claude run was interrupted.", exception);
        }
    }

    private void verifyNoApiKey(StreamEventParser parser, Process process) throws ApiKeyDetectedException {
        Optional<String> source = parser.apiKeySource();
        if (source.isPresent() && !NO_API_KEY.equals(source.get())) {
            endProcessTree(process);
            throw new ApiKeyDetectedException("The session uses the credential source: " + source.get());
        }
    }

    private SessionReport buildReport(PhaseRequest request, Process process, StreamEventParser parser)
            throws InterruptedException, ApiKeyDetectedException {
        int exitCode = process.waitFor();
        SessionReport report = parser.report();
        if (report.success() && report.apiKeySource().isEmpty()) {
            throw new ApiKeyDetectedException(UNKNOWN_SOURCE_MESSAGE);
        }
        boolean stopped = stoppedIssues.containsKey(request.issueNumber());
        if (report.success() && (exitCode != 0 || stopped)) {
            return new SessionReport(false, report.resultText(), report.apiKeySource(),
                    report.contextUsage(), report.usageWindows());
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

    private void endProcessTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
