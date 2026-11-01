package com.meterian.factory.github;

import com.meterian.factory.state.IssueStatus;
import java.util.List;
import java.util.Optional;

public interface GitHubClient {

    String authenticatedLogin();

    List<Issue> openIssuesWithLabel(String label);

    Issue issue(int number);

    List<IssueComment> comments(int issueNumber);

    long createComment(int issueNumber, String body);

    void editComment(long commentId, String body);

    /** Returns the actor of the newest "labeled" event for this label. */
    Optional<String> lastLabelActor(int issueNumber, String label);

    void ensureLabel(String label, String color);

    void addLabel(int issueNumber, String label);

    /** Does nothing when the label is not on the issue. */
    void removeLabel(int issueNumber, String label);

    /** Adds the issue to the Project if it is not there, then sets the field "Status". */
    void setStatus(Issue issue, IssueStatus status);

    Optional<PullRequest> openPullRequest(String headBranch);

    PullRequest createPullRequest(String headBranch, String baseBranch, String title, String body);

    MergeOutcome squashMerge(int pullRequestNumber);
}
