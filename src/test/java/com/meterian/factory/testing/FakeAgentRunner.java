package com.meterian.factory.testing;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.AgentRunner;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.PhaseRefusedException;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.agent.SessionReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An agent runner for the workflow tests. It runs one scripted action for each phase, in order. */
public final class FakeAgentRunner implements AgentRunner {

    /** One scripted phase. It works in the worktree of the request. */
    @FunctionalInterface
    public interface Action {
        void perform(PhaseRequest request) throws IOException, ApiKeyDetectedException;
    }

    private static final Pattern RESULT_FILE = Pattern.compile("\\.factory-work/((review-\\w+|risk)\\.json)");
    private static final SessionReport SUCCESS = new SessionReport(true, "done", Optional.of("none"),
            Optional.empty(), List.of());
    private static final SessionReport FAILURE = new SessionReport(false, "stopped", Optional.of("none"),
            Optional.empty(), List.of());

    private static final SessionReport STOPPED = new SessionReport(false, "The action was stopped.", Optional.empty(),
            Optional.empty(), List.of(), true);

    private record ScriptedPhase(Action action, SessionReport report) {
    }

    private final Deque<ScriptedPhase> phases = new ArrayDeque<>();
    private final List<PhaseRequest> requests = new ArrayList<>();
    private final List<Integer> stoppedIssues = new CopyOnWriteArrayList<>();
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final AtomicInteger waitingPhases = new AtomicInteger();
    private volatile boolean refusesNewPhases;
    private volatile boolean sessionFileIsSaved = true;
    private int changeCount;

    public synchronized FakeAgentRunner then(Action action) {
        phases.add(new ScriptedPhase(action, SUCCESS));
        return this;
    }

    /** The last scripted phase does its action, but its session does not end with success. */
    public synchronized FakeAgentRunner thatFails() {
        ScriptedPhase last = phases.removeLast();
        phases.add(new ScriptedPhase(last.action(), FAILURE));
        return this;
    }

    /** The stop comes so early that claude has no session file yet. */
    public FakeAgentRunner withoutASavedSession() {
        sessionFileIsSaved = false;
        return this;
    }

    /** The last scripted phase does its action, and then its session reports the stop of the worker. */
    public synchronized FakeAgentRunner thatIsStopped() {
        ScriptedPhase last = phases.removeLast();
        phases.add(new ScriptedPhase(last.action(), STOPPED));
        return this;
    }

    /** The session of the last scripted phase reports this context usage. */
    public synchronized FakeAgentRunner withContextUsage(long tokensInUse, long contextWindow) {
        ScriptedPhase last = phases.removeLast();
        SessionReport report = last.report();
        phases.add(new ScriptedPhase(last.action(), new SessionReport(report.success(), report.resultText(),
                report.apiKeySource(), Optional.of(new ContextUsage(tokensInUse, contextWindow)),
                report.usageWindows())));
        return this;
    }

    public synchronized FakeAgentRunner thenChangeFile() {
        return then(this::changeWorkFile);
    }

    /** Changes a file, then runs the side effect, for example a GitHub failure. */
    public synchronized FakeAgentRunner thenChangeFileAnd(Runnable sideEffect) {
        return then(request -> {
            changeWorkFile(request);
            sideEffect.run();
        });
    }

    public synchronized FakeAgentRunner thenChangeNothing() {
        then(request -> {
        });
        return this;
    }

    public synchronized FakeAgentRunner thenAskQuestion(String question) {
        then(request -> {
            writeFile(request, "work.txt", "half done\n");
            writeFile(request, ".factory-work/question.md", question);
        });
        return this;
    }

    public synchronized FakeAgentRunner thenApproveReview() {
        then(request -> writeResult(request,
                "{\"verdict\": \"approve\", \"tree\": \"" + currentTree(request) + "\", \"findings\": []}"));
        return this;
    }

    public synchronized FakeAgentRunner thenApproveReviewWithNote(String noteTitle, String noteDetail) {
        then(request -> writeResult(request, "{\"verdict\": \"approve\", \"tree\": \"" + currentTree(request)
                + "\", \"findings\": [], \"notes\": [{\"title\": \"" + noteTitle + "\", \"detail\": \""
                + noteDetail + "\"}]}"));
        return this;
    }

    public synchronized FakeAgentRunner thenApproveAllReviews() {
        return thenApproveAllReviews(4);
    }

    public synchronized FakeAgentRunner thenApproveAllReviews(int reviewCount) {
        for (int review = 0; review < reviewCount; review++) {
            thenApproveReview();
        }
        return this;
    }

    public synchronized FakeAgentRunner thenApproveReviewOfTree(String treeHash) {
        then(request -> writeResult(request,
                "{\"verdict\": \"approve\", \"tree\": \"" + treeHash + "\", \"findings\": []}"));
        return this;
    }

    public synchronized FakeAgentRunner thenApproveReviewOfTreeWithNote(String treeHash, String noteTitle,
                                                                        String noteDetail) {
        then(request -> writeResult(request, "{\"verdict\": \"approve\", \"tree\": \"" + treeHash
                + "\", \"findings\": [], \"notes\": [{\"title\": \"" + noteTitle + "\", \"detail\": \""
                + noteDetail + "\"}]}"));
        return this;
    }

    public synchronized FakeAgentRunner thenRejectReview(String findingTitle) {
        then(request -> writeResult(request, "{\"verdict\": \"reject\", \"tree\": \"" + currentTree(request)
                + "\", \"findings\": [{\"title\": \"" + findingTitle + "\", \"detail\": \"Fix it.\"}]}"));
        return this;
    }

    public synchronized FakeAgentRunner thenAssessRisk(String risk, boolean small) {
        then(request -> writeResult(request,
                "{\"risk\": \"" + risk + "\", \"small\": " + small + ", \"reasons\": [\"scripted\"]}"));
        return this;
    }

    /** Writes the risk result, then runs the side effect in the worktree, as a confused risk session does. */
    public synchronized FakeAgentRunner thenAssessRiskAnd(String risk, boolean small, Action sideEffect) {
        then(request -> {
            writeResult(request, "{\"risk\": \"" + risk + "\", \"small\": " + small + ", \"reasons\": [\"scripted\"]}");
            sideEffect.perform(request);
        });
        return this;
    }

    public synchronized FakeAgentRunner thenDetectAnApiKey() {
        then(request -> {
            throw new ApiKeyDetectedException("The session uses the API key source ANTHROPIC_API_KEY.");
        });
        return this;
    }

    /** The phase waits until a call of stopPhase, as a long claude session does. Add thatFails for a stopped run. */
    public synchronized FakeAgentRunner thenWaitForStop() {
        then(request -> waitForStop());
        return this;
    }

    /** The number of phases that wait for a stop now or waited before. It does not need the lock of the runner. */
    public int waitingPhaseCount() {
        return waitingPhases.get();
    }

    public List<Integer> stoppedIssues() {
        return List.copyOf(stoppedIssues);
    }

    public synchronized List<PhaseRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public synchronized SessionReport runPhase(PhaseRequest request) throws AgentException, ApiKeyDetectedException {
        if (refusesNewPhases) {
            throw new PhaseRefusedException("The factory is stopping.");
        }
        requests.add(request);
        ScriptedPhase phase = phases.poll();
        if (phase == null) {
            throw new IllegalStateException("The test has no scripted action for the phase " + requests.size());
        }
        try {
            phase.action().perform(request);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return phase.report();
    }

    /** Not synchronized: a waiting phase holds the lock of the runner. */
    @Override
    public void refuseNewPhases() {
        refusesNewPhases = true;
    }

    /** Not synchronized: a waiting phase holds the lock of the runner. */
    @Override
    public boolean hasSavedSession(UUID sessionId, Path worktree) {
        return sessionFileIsSaved;
    }

    /** Not synchronized: a waiting phase holds the lock of the runner. */
    @Override
    public void stopPhase(int issueNumber) {
        stoppedIssues.add(issueNumber);
        stopSignal.countDown();
    }

    private void waitForStop() {
        waitingPhases.incrementAndGet();
        try {
            if (!stopSignal.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The phase got no stop in 30 seconds");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The wait for the stop was interrupted", exception);
        }
    }

    private synchronized void changeWorkFile(PhaseRequest request) throws IOException {
        writeFile(request, "work.txt", "change " + ++changeCount + "\n");
    }

    private static void writeResult(PhaseRequest request, String json) throws IOException {
        Matcher matcher = RESULT_FILE.matcher(request.prompt());
        if (!matcher.find()) {
            throw new IllegalStateException("The prompt names no result file");
        }
        writeFile(request, ".factory-work/" + matcher.group(1), json);
    }

    private static void writeFile(PhaseRequest request, String relativePath, String content) throws IOException {
        Path file = request.workingDirectory().resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String currentTree(PhaseRequest request) {
        return TemporaryRepository.run(request.workingDirectory(), "rev-parse", "HEAD^{tree}");
    }
}
