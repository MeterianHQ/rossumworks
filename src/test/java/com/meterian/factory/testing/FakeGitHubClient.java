package com.meterian.factory.testing;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.github.MergeOutcome;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.state.IssueStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** An in-memory GitHub for the workflow tests. All methods are thread safe. */
public final class FakeGitHubClient implements GitHubClient {

    private record LabelEvent(int issueNumber, String label, String actor) {
    }

    private final Map<Integer, Issue> issues = new TreeMap<>();
    private final Map<Integer, Set<String>> labels = new TreeMap<>();
    private final Map<Integer, List<IssueComment>> commentsByIssue = new TreeMap<>();
    private final Map<Integer, IssueStatus> statuses = new TreeMap<>();
    private final List<LabelEvent> labelEvents = new ArrayList<>();
    private final List<PullRequest> pullRequests = new ArrayList<>();
    private final Deque<MergeOutcome> mergeOutcomes = new ArrayDeque<>();
    private final List<Integer> squashMergeCalls = new ArrayList<>();
    private String authenticatedLogin = "factory-bot";
    private long nextCommentId = 1000;
    private int nextPullRequestNumber = 500;

    public synchronized void setAuthenticatedLogin(String login) {
        authenticatedLogin = login;
    }

    public synchronized void addIssue(int number, String title, String body) {
        issues.put(number, new Issue(number, "NODE" + number, title, body, Set.of()));
        labels.put(number, new LinkedHashSet<>());
        commentsByIssue.put(number, new ArrayList<>());
    }

    public synchronized void addLabelBy(int issueNumber, String label, String actor) {
        labels.get(issueNumber).add(label);
        labelEvents.add(new LabelEvent(issueNumber, label, actor));
    }

    public synchronized long addCommentBy(int issueNumber, String author, String body) {
        long id = nextCommentId++;
        commentsByIssue.get(issueNumber).add(new IssueComment(id, author, body));
        return id;
    }

    public synchronized Optional<IssueStatus> statusOf(int issueNumber) {
        return Optional.ofNullable(statuses.get(issueNumber));
    }

    public synchronized List<IssueComment> commentsOf(int issueNumber) {
        return List.copyOf(commentsByIssue.get(issueNumber));
    }

    public synchronized Set<String> labelsOf(int issueNumber) {
        return Set.copyOf(labels.get(issueNumber));
    }

    public synchronized List<PullRequest> pullRequests() {
        return List.copyOf(pullRequests);
    }

    public synchronized void queueMergeOutcomes(MergeOutcome... outcomes) {
        mergeOutcomes.addAll(List.of(outcomes));
    }

    public synchronized List<Integer> squashMergeCalls() {
        return List.copyOf(squashMergeCalls);
    }

    @Override
    public synchronized String authenticatedLogin() {
        return authenticatedLogin;
    }

    @Override
    public synchronized List<Issue> openIssuesWithLabel(String label) {
        return issues.keySet().stream().filter(number -> labels.get(number).contains(label)).map(this::issue).toList();
    }

    @Override
    public synchronized Issue issue(int number) {
        Issue issue = issues.get(number);
        if (issue == null) {
            throw new GitHubException("GitHub answered 404 for GET /issues/" + number);
        }
        return new Issue(issue.number(), issue.nodeId(), issue.title(), issue.body(), Set.copyOf(labels.get(number)));
    }

    @Override
    public synchronized List<IssueComment> comments(int issueNumber) {
        return commentsOf(issueNumber);
    }

    @Override
    public synchronized long createComment(int issueNumber, String body) {
        return addCommentBy(issueNumber, authenticatedLogin, body);
    }

    @Override
    public synchronized void editComment(long commentId, String body) {
        for (List<IssueComment> comments : commentsByIssue.values()) {
            comments.replaceAll(comment -> comment.id() == commentId
                    ? new IssueComment(commentId, comment.authorLogin(), body) : comment);
        }
    }

    @Override
    public synchronized Optional<String> lastLabelActor(int issueNumber, String label) {
        return labelEvents.reversed().stream()
                .filter(event -> event.issueNumber() == issueNumber && event.label().equals(label))
                .map(LabelEvent::actor)
                .findFirst();
    }

    @Override
    public synchronized void ensureLabel(String label, String color) {
    }

    @Override
    public synchronized void addLabel(int issueNumber, String label) {
        addLabelBy(issueNumber, label, authenticatedLogin);
    }

    @Override
    public synchronized void removeLabel(int issueNumber, String label) {
        labels.get(issueNumber).remove(label);
    }

    @Override
    public synchronized void setStatus(Issue issue, IssueStatus status) {
        statuses.put(issue.number(), status);
    }

    @Override
    public synchronized Optional<PullRequest> openPullRequest(String headBranch) {
        return pullRequests.stream().filter(pullRequest -> pullRequest.headBranch().equals(headBranch)).findFirst();
    }

    @Override
    public synchronized PullRequest createPullRequest(String headBranch, String baseBranch, String title,
                                                      String body) {
        int number = nextPullRequestNumber++;
        PullRequest pullRequest = new PullRequest(number, "https://github.test/pull/" + number, headBranch);
        pullRequests.add(pullRequest);
        return pullRequest;
    }

    @Override
    public synchronized MergeOutcome squashMerge(int pullRequestNumber) {
        squashMergeCalls.add(pullRequestNumber);
        return mergeOutcomes.isEmpty() ? MergeOutcome.MERGED : mergeOutcomes.poll();
    }
}
