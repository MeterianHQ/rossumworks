package com.meterian.factory.github;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface GitHubClient {

    String authenticatedLogin();

    List<Issue> openIssuesWithLabel(String label);

    Issue issue(int number);

    List<IssueComment> comments(int issueNumber);

    long createComment(int issueNumber, String body);

    void editComment(long commentId, String body);

    /**
     * Adds the login to the assignees. A pull request is an issue for this call. GitHub does nothing for a login
     * that has no access to the repository.
     */
    void addAssignee(int issueNumber, String login);

    /** Does nothing when the login is not an assignee of the issue. */
    void removeAssignee(int issueNumber, String login);

    /** Returns the "assigned" events of the issue, in the order of GitHub. */
    List<AssignmentEvent> assignmentEvents(int issueNumber);

    /** Creates the label when it does not exist. Does nothing when it exists. */
    void ensureLabel(String label, String color, String description);

    default void ensureLabel(String label, String color) {
        ensureLabel(label, color, "");
    }

    void addLabel(int issueNumber, String label);

    /** Does nothing when the label is not on the issue. */
    void removeLabel(int issueNumber, String label);

    /** Makes the label of the column the only column label on the issue. */
    void setColumn(Issue issue, String column);

    Optional<PullRequest> openPullRequest(String headBranch);

    PullRequest createPullRequest(String headBranch, String baseBranch, String title, String body);

    void editPullRequestBody(int pullRequestNumber, String body);

    /** Returns the commit hash of the head of the branch on GitHub. Empty when GitHub does not have the branch. */
    Optional<String> findBranchHead(String branch);

    /** Tells if GitHub merged the pull request. */
    boolean isMerged(int pullRequestNumber);

    /** Returns the commit hash of the head of the pull request on GitHub. */
    String pullRequestHead(int pullRequestNumber);

    /** Returns the tree hash of the merge commit of a merged pull request. Empty when GitHub has no merge commit. */
    Optional<String> mergeCommitTree(int pullRequestNumber);

    /** Returns the commit hash of the merge commit of a merged pull request. Empty when GitHub has no merge commit. */
    Optional<String> mergeCommit(int pullRequestNumber);

    /**
     * Returns the first page of the check runs of the commit, with one request. GitHub gives 100 check runs or
     * less in one answer. The page also gives the total count, so that the caller sees a missing check run.
     */
    CheckRunPage checkRuns(String commit);

    /** Merges only if the head of the pull request is the expected commit. */
    MergeOutcome squashMerge(int pullRequestNumber, String expectedHeadSha);

    /**
     * Returns the number of requests since the last call, by method and path template, and starts the counts
     * again at zero. A client that does not count returns an empty map.
     */
    default Map<String, Integer> takeRequestCounts() {
        return Map.of();
    }

    /**
     * Returns the reset time of the rate-limit window when few requests remain in it. The poll then starts no
     * work. A client that does not read the rate limit returns empty.
     */
    default Optional<Instant> findLowRemainingReset() {
        return Optional.empty();
    }
}
