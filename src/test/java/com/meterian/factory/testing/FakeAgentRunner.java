package com.meterian.factory.testing;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.AgentRunner;
import com.meterian.factory.agent.ApiKeyDetectedException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An agent runner for the workflow tests. It runs one scripted action for each phase, in order. */
public final class FakeAgentRunner implements AgentRunner {

    /** One scripted phase. It works in the worktree of the request. */
    @FunctionalInterface
    public interface Action {
        void perform(PhaseRequest request) throws IOException, ApiKeyDetectedException;
    }

    private static final Pattern RESULT_FILE = Pattern.compile("\\.factory/((review-\\w+|risk)\\.json)");
    private static final SessionReport SUCCESS = new SessionReport(true, "done", Optional.of("none"),
            Optional.empty(), List.of());

    private final Deque<Action> actions = new ArrayDeque<>();
    private final List<PhaseRequest> requests = new ArrayList<>();
    private int changeCount;

    public synchronized FakeAgentRunner thenChangeFile() {
        actions.add(request -> writeFile(request, "work.txt", "change " + ++changeCount + "\n"));
        return this;
    }

    public synchronized FakeAgentRunner thenChangeNothing() {
        actions.add(request -> {
        });
        return this;
    }

    public synchronized FakeAgentRunner thenAskQuestion(String question) {
        actions.add(request -> {
            writeFile(request, "work.txt", "half done\n");
            writeFile(request, ".factory/question.md", question);
        });
        return this;
    }

    public synchronized FakeAgentRunner thenApproveReview() {
        actions.add(request -> writeResult(request,
                "{\"verdict\": \"approve\", \"tree\": \"" + currentTree(request) + "\", \"findings\": []}"));
        return this;
    }

    public synchronized FakeAgentRunner thenApproveAllReviews() {
        for (int review = 0; review < 4; review++) {
            thenApproveReview();
        }
        return this;
    }

    public synchronized FakeAgentRunner thenApproveReviewOfTree(String treeHash) {
        actions.add(request -> writeResult(request,
                "{\"verdict\": \"approve\", \"tree\": \"" + treeHash + "\", \"findings\": []}"));
        return this;
    }

    public synchronized FakeAgentRunner thenRejectReview(String findingTitle) {
        actions.add(request -> writeResult(request, "{\"verdict\": \"reject\", \"tree\": \"" + currentTree(request)
                + "\", \"findings\": [{\"title\": \"" + findingTitle + "\", \"detail\": \"Fix it.\"}]}"));
        return this;
    }

    public synchronized FakeAgentRunner thenAssessRisk(String risk, boolean small) {
        actions.add(request -> writeResult(request,
                "{\"risk\": \"" + risk + "\", \"small\": " + small + ", \"reasons\": [\"scripted\"]}"));
        return this;
    }

    public synchronized FakeAgentRunner thenDetectAnApiKey() {
        actions.add(request -> {
            throw new ApiKeyDetectedException("The session uses the API key source ANTHROPIC_API_KEY.");
        });
        return this;
    }

    public synchronized List<PhaseRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public synchronized SessionReport runPhase(PhaseRequest request) throws AgentException, ApiKeyDetectedException {
        requests.add(request);
        Action action = actions.poll();
        if (action == null) {
            throw new IllegalStateException("The test has no scripted action for the phase " + requests.size());
        }
        try {
            action.perform(request);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return SUCCESS;
    }

    @Override
    public void stopPhase(int issueNumber) {
    }

    private static void writeResult(PhaseRequest request, String json) throws IOException {
        Matcher matcher = RESULT_FILE.matcher(request.prompt());
        if (!matcher.find()) {
            throw new IllegalStateException("The prompt names no result file");
        }
        writeFile(request, ".factory/" + matcher.group(1), json);
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
