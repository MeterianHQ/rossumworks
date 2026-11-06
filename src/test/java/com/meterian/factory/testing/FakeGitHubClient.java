package com.meterian.factory.testing;

import com.meterian.factory.git.GitException;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.github.MergeOutcome;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.state.ColumnLabels;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** An in-memory GitHub for the workflow tests. All methods are thread safe. */
public final class FakeGitHubClient implements GitHubClient {

    private final Map<Integer, Issue> issues = new TreeMap<>();
    private final Map<Integer, Set<String>> labels = new TreeMap<>();
    private final Map<Integer, List<String>> assignees = new TreeMap<>();
    private final Set<String> createdLabels = new LinkedHashSet<>();
    private final Map<Integer, List<IssueComment>> commentsByIssue = new TreeMap<>();
    private final List<PullRequest> pullRequests = new ArrayList<>();
    private final Set<Integer> mergedPullRequests = new HashSet<>();
    private final Deque<MergeOutcome> mergeOutcomes = new ArrayDeque<>();
    private final List<Integer> squashMergeCalls = new ArrayList<>();
    private final List<String> squashMergeShas = new ArrayList<>();
    private final Map<Integer, String> baseBranches = new TreeMap<>();
    private final Map<Integer, String> mergeCommitTrees = new TreeMap<>();
    private SquashMergeSimulation squashMergeSimulation;
    private Path pullRequestRepository;
    private boolean failsTheNextSquashMergeAfterTheMerge;
    private boolean answersAnOldPullRequestHead;
    private String authenticatedLogin = "factory-bot";
    private long nextCommentId = 1000;
    private int nextPullRequestNumber = 500;
    private boolean failsTheNextCommentEdit;
    private boolean failsTheNextCommentCreate;
    private boolean failsTheNextPullRequestCreation;
    private boolean failsTheNextAssignment;
    private boolean failsTheCommentEditAfterTheMerge;
    private final Set<Integer> issuesWithFailingComments = new HashSet<>();
    private final List<String> editedBodies = new ArrayList<>();
    private final List<String> editedPullRequestBodies = new ArrayList<>();
    private final List<String> removedLabels = new ArrayList<>();
    private RuntimeException removeLabelFailureAfterTheMerge;
    private RuntimeException nextRemoveLabelFailure;
    private boolean failsTheNextIssueList;
    private boolean failsTheNextPullRequestBodyEdit;
    private Runnable pullRequestBodyEditAction = () -> { };
    private Runnable squashMergeAction = () -> { };
    private String commentEditTrigger;
    private Runnable commentEditAction = () -> { };

    public synchronized void setAuthenticatedLogin(String login) {
        authenticatedLogin = login;
    }

    public synchronized void addIssue(int number, String title, String body) {
        issues.put(number, new Issue(number, title, body, Set.of()));
        labels.put(number, new LinkedHashSet<>());
        commentsByIssue.put(number, new ArrayList<>());
    }

    /** A person edits the title and the body of the issue. */
    public synchronized void editIssue(int number, String title, String body) {
        issues.put(number, new Issue(number, title, body, Set.of()));
    }

    public synchronized long addCommentBy(int issueNumber, String author, String body) {
        long id = nextCommentId++;
        commentsByIssue.get(issueNumber).add(new IssueComment(id, author, body));
        return id;
    }

    /** The column of the one column label of the issue. Empty when the issue has no column label or several. */
    public synchronized Optional<String> columnOf(int issueNumber) {
        Set<String> columnLabels = columnLabelsOf(issueNumber);
        if (columnLabels.size() != 1) {
            return Optional.empty();
        }
        String label = columnLabels.iterator().next();
        return Optional.of(label.substring(ColumnLabels.labelName("").length()));
    }

    public synchronized Set<String> columnLabelsOf(int issueNumber) {
        return labels.get(issueNumber).stream().filter(ColumnLabels::isColumnLabel)
                .collect(Collectors.toCollection(LinkedHashSet::new));
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

    public synchronized List<String> squashMergeShas() {
        return List.copyOf(squashMergeShas);
    }

    /** The next createComment throws a GitHubException, as a lost connection does. */
    public synchronized void failTheNextCommentCreate() {
        failsTheNextCommentCreate = true;
    }

    /** The next createPullRequest throws a GitHubException, as a lost connection does. */
    public synchronized void failTheNextPullRequestCreation() {
        failsTheNextPullRequestCreation = true;
    }

    /** The next assignIssue throws a GitHubException, as a refused assignment does. */
    public synchronized void failTheNextAssignment() {
        failsTheNextAssignment = true;
    }

    /** The next editComment throws a GitHubException, as a lost connection does. */
    public synchronized void failTheNextCommentEdit() {
        failsTheNextCommentEdit = true;
    }

    /** The first editComment after a successful squash merge throws a GitHubException. */
    public synchronized void failTheCommentEditAfterTheMerge() {
        failsTheCommentEditAfterTheMerge = true;
    }

    /** The next squash merge runs the action first, in the thread of the merge. */
    public synchronized void whenSquashMerging(Runnable action) {
        squashMergeAction = action;
    }

    /** Each successful squash merge writes a squash commit on the origin of the clone, as GitHub does. */
    public synchronized void squashMergeInto(Path clone) {
        squashMergeSimulation = new SquashMergeSimulation(clone);
    }

    /** The head of a pull request is the branch of the pull request in this bare repository, as on GitHub. */
    public synchronized void readPullRequestHeadsFrom(Path repository) {
        pullRequestRepository = repository;
    }

    /** The next pullRequestHead gives an old head, as GitHub does for a short time after a push. */
    public synchronized void answerAnOldPullRequestHeadOnce() {
        answersAnOldPullRequestHead = true;
    }

    /** The next successful squash merge merges, then throws a GitHubException, as a lost answer does. */
    public synchronized void failTheNextSquashMergeAfterTheMerge() {
        failsTheNextSquashMergeAfterTheMerge = true;
    }

    /** The first comment edit with a body that contains the text runs the action first, in the same thread. */
    public synchronized void whenACommentEditContains(String text, Runnable action) {
        commentEditTrigger = text;
        commentEditAction = action;
    }

    /** Each body that editComment wrote, in order. */
    public synchronized List<String> editedBodies() {
        return List.copyOf(editedBodies);
    }

    /** Each body that editPullRequestBody wrote, in order. */
    public synchronized List<String> editedPullRequestBodies() {
        return List.copyOf(editedPullRequestBodies);
    }

    /** The next editPullRequestBody throws a GitHubException. */
    public synchronized void failTheNextPullRequestBodyEdit() {
        failsTheNextPullRequestBodyEdit = true;
    }

    /** Runs the action at each editPullRequestBody, before the edit. */
    public synchronized void runOnPullRequestBodyEdit(Runnable action) {
        pullRequestBodyEditAction = action;
    }

    /** Each label that removeLabel removed from an issue, in order. */
    public synchronized List<String> removedLabels() {
        return List.copyOf(removedLabels);
    }

    /** The next removeLabel throws a GitHubException. */
    public synchronized void failTheNextRemoveLabel() {
        nextRemoveLabelFailure = new GitHubException("GitHub answered 502 for DELETE /labels");
    }

    /** The first removeLabel after a successful squash merge throws a GitHubException. */
    public synchronized void failTheRemoveLabelAfterTheMerge() {
        removeLabelFailureAfterTheMerge = new GitHubException("GitHub answered 502 for DELETE /labels");
    }

    /** The first removeLabel after a successful squash merge throws an IllegalStateException, as a defect does. */
    public synchronized void failTheRemoveLabelAfterTheMergeWithADefect() {
        removeLabelFailureAfterTheMerge = new IllegalStateException("A defect in the removal of a label");
    }

    /** The next openIssuesWithLabel throws an IllegalStateException, as an unexpected defect does. */
    public synchronized void failTheNextIssueList() {
        failsTheNextIssueList = true;
    }

    /** The comments of the issue throw an IllegalStateException, as an unexpected defect does. */
    public synchronized void failTheCommentsOf(int issueNumber) {
        issuesWithFailingComments.add(issueNumber);
    }

    @Override
    public synchronized String authenticatedLogin() {
        return authenticatedLogin;
    }

    @Override
    public synchronized List<Issue> openIssuesWithLabel(String label) {
        if (failsTheNextIssueList) {
            failsTheNextIssueList = false;
            throw new IllegalStateException("A defect in the list of the issues");
        }
        return issues.keySet().stream().filter(number -> labels.get(number).contains(label)).map(this::issue).toList();
    }

    @Override
    public synchronized Issue issue(int number) {
        Issue issue = issues.get(number);
        if (issue == null) {
            throw new GitHubException("GitHub answered 404 for GET /issues/" + number);
        }
        return new Issue(issue.number(), issue.title(), issue.body(), Set.copyOf(labels.get(number)));
    }

    @Override
    public synchronized List<IssueComment> comments(int issueNumber) {
        if (issuesWithFailingComments.contains(issueNumber)) {
            throw new IllegalStateException("A defect in the comments of the issue " + issueNumber);
        }
        return commentsOf(issueNumber);
    }

    @Override
    public synchronized long createComment(int issueNumber, String body) {
        if (failsTheNextCommentCreate) {
            failsTheNextCommentCreate = false;
            throw new GitHubException("GitHub answered 502 for POST /issues/" + issueNumber + "/comments");
        }
        return addCommentBy(issueNumber, authenticatedLogin, body);
    }

    @Override
    public synchronized void editComment(long commentId, String body) {
        if (commentEditTrigger != null && body.contains(commentEditTrigger)) {
            commentEditTrigger = null;
            commentEditAction.run();
        }
        if (failsTheNextCommentEdit) {
            failsTheNextCommentEdit = false;
            throw new GitHubException("GitHub answered 502 for PATCH /issues/comments/" + commentId);
        }
        editedBodies.add(body);
        for (List<IssueComment> comments : commentsByIssue.values()) {
            comments.replaceAll(comment -> comment.id() == commentId
                    ? new IssueComment(commentId, comment.authorLogin(), body) : comment);
        }
    }

    @Override
    public synchronized void ensureLabel(String label, String color, String description) {
        createdLabels.add(label);
    }

    /** The names of the labels that the factory created, in the order of the creation. */
    public synchronized Set<String> createdLabels() {
        return new LinkedHashSet<>(createdLabels);
    }

    @Override
    public synchronized void assignIssue(int issueNumber, String login) {
        if (failsTheNextAssignment) {
            failsTheNextAssignment = false;
            throw new GitHubException("GitHub answered 422 for POST /issues/" + issueNumber + "/assignees");
        }
        assignees.computeIfAbsent(issueNumber, number -> new ArrayList<>()).add(login);
    }

    public synchronized List<String> assigneesOf(int issueNumber) {
        return List.copyOf(assignees.getOrDefault(issueNumber, List.of()));
    }

    @Override
    public synchronized void addLabel(int issueNumber, String label) {
        labels.get(issueNumber).add(label);
    }

    @Override
    public synchronized void removeLabel(int issueNumber, String label) {
        if (nextRemoveLabelFailure != null) {
            RuntimeException failure = nextRemoveLabelFailure;
            nextRemoveLabelFailure = null;
            throw failure;
        }
        if (labels.get(issueNumber).remove(label)) {
            removedLabels.add(label);
        }
    }

    @Override
    public synchronized void setColumn(Issue issue, String column) {
        String wantedLabel = ColumnLabels.labelName(column);
        labels.get(issue.number()).removeIf(label -> ColumnLabels.isColumnLabel(label) && !label.equals(wantedLabel));
        if (!createdLabels.contains(wantedLabel)) {
            ensureLabel(wantedLabel, ColumnLabels.colorOf(column), ColumnLabels.describe(column));
        }
        addLabel(issue.number(), wantedLabel);
    }

    @Override
    public synchronized Optional<PullRequest> openPullRequest(String headBranch) {
        return pullRequests.stream()
                .filter(pullRequest -> pullRequest.headBranch().equals(headBranch))
                .filter(pullRequest -> !mergedPullRequests.contains(pullRequest.number()))
                .findFirst();
    }

    @Override
    public synchronized PullRequest createPullRequest(String headBranch, String baseBranch, String title,
                                                      String body) {
        if (failsTheNextPullRequestCreation) {
            failsTheNextPullRequestCreation = false;
            throw new GitHubException("GitHub answered 502 for POST /pulls");
        }
        verifyBranchIsOnGitHub(headBranch);
        int number = nextPullRequestNumber++;
        PullRequest pullRequest = new PullRequest(number, "https://github.test/pull/" + number, headBranch, body);
        pullRequests.add(pullRequest);
        baseBranches.put(number, baseBranch);
        return pullRequest;
    }

    /** GitHub answers 422 for a pull request with a head branch that it does not have. */
    private void verifyBranchIsOnGitHub(String branch) {
        if (pullRequestRepository != null && readBranchHead(branch).isEmpty()) {
            throw new GitHubException("GitHub answered 422 for POST /pulls");
        }
    }

    private Optional<String> readBranchHead(String branch) {
        try {
            return Optional.of(TemporaryRepository.run(pullRequestRepository, "rev-parse", "--verify",
                    "refs/heads/" + branch));
        } catch (GitException missingBranch) {
            return Optional.empty();
        }
    }

    @Override
    public synchronized Optional<String> findBranchHead(String branch) {
        return readBranchHead(branch);
    }

    /** A pull request that GitHub merged before the test starts. */
    public synchronized int addMergedPullRequest(String branch) {
        int number = nextPullRequestNumber++;
        pullRequests.add(new PullRequest(number, "https://github.test/pull/" + number, branch, ""));
        mergedPullRequests.add(number);
        return number;
    }

    @Override
    public synchronized void editPullRequestBody(int pullRequestNumber, String body) {
        pullRequestBodyEditAction.run();
        if (failsTheNextPullRequestBodyEdit) {
            failsTheNextPullRequestBodyEdit = false;
            throw new GitHubException("GitHub answered 502 for PATCH /pulls/" + pullRequestNumber);
        }
        pullRequests.replaceAll(pullRequest -> pullRequest.number() == pullRequestNumber
                ? new PullRequest(pullRequestNumber, pullRequest.url(), pullRequest.headBranch(), body)
                : pullRequest);
        editedPullRequestBodies.add(body);
    }

    @Override
    public synchronized MergeOutcome squashMerge(int pullRequestNumber, String expectedHeadSha) {
        Runnable action = squashMergeAction;
        squashMergeAction = () -> { };
        action.run();
        squashMergeCalls.add(pullRequestNumber);
        squashMergeShas.add(expectedHeadSha);
        MergeOutcome outcome = mergeOutcomes.isEmpty() ? MergeOutcome.MERGED : mergeOutcomes.poll();
        if (outcome == MergeOutcome.MERGED && !simulateSquashMerge(pullRequestNumber, expectedHeadSha)) {
            return MergeOutcome.NOT_MERGEABLE;
        }
        if (outcome == MergeOutcome.MERGED) {
            mergedPullRequests.add(pullRequestNumber);
            failsTheNextCommentEdit = failsTheNextCommentEdit || failsTheCommentEditAfterTheMerge;
            failsTheCommentEditAfterTheMerge = false;
            if (removeLabelFailureAfterTheMerge != null) {
                nextRemoveLabelFailure = removeLabelFailureAfterTheMerge;
                removeLabelFailureAfterTheMerge = null;
            }
            if (failsTheNextSquashMergeAfterTheMerge) {
                failsTheNextSquashMergeAfterTheMerge = false;
                throw new GitHubException("the request PUT /pulls/" + pullRequestNumber + "/merge failed: "
                        + "HttpTimeoutException");
            }
        }
        return outcome;
    }

    @Override
    public synchronized boolean isMerged(int pullRequestNumber) {
        return mergedPullRequests.contains(pullRequestNumber);
    }

    /** A missing branch gives an empty head. A test that pushes must name the repository first. */
    @Override
    public synchronized String pullRequestHead(int pullRequestNumber) {
        if (pullRequestRepository == null) {
            throw new IllegalStateException("The test did not name the repository of the pull request heads.");
        }
        if (answersAnOldPullRequestHead) {
            answersAnOldPullRequestHead = false;
            return "0000000000000000000000000000000000000000";
        }
        String branch = pullRequests.stream().filter(pullRequest -> pullRequest.number() == pullRequestNumber)
                .findFirst().orElseThrow().headBranch();
        try {
            return TemporaryRepository.run(pullRequestRepository, "rev-parse", "--verify", "refs/heads/" + branch);
        } catch (GitException missingBranch) {
            return "";
        }
    }

    @Override
    public synchronized Optional<String> mergeCommitTree(int pullRequestNumber) {
        return Optional.ofNullable(mergeCommitTrees.get(pullRequestNumber));
    }

    /** Without a simulation, a merge always succeeds and leaves the origin as it is. */
    private boolean simulateSquashMerge(int pullRequestNumber, String headCommit) {
        if (squashMergeSimulation == null) {
            return true;
        }
        Optional<String> tree = squashMergeSimulation.squashMerge(headCommit, baseBranches.get(pullRequestNumber));
        tree.ifPresent(mergedTree -> mergeCommitTrees.put(pullRequestNumber, mergedTree));
        return tree.isPresent();
    }
}
