package com.meterian.factory.workflow;

import com.meterian.factory.github.GitHubClient;
import java.util.List;
import java.util.Optional;

/** Tells who gave a request label. Fails closed: a label with no event is not a request. */
public final class RequestReader {

    private final GitHubClient gitHub;
    private final List<String> allowedAccounts;

    public RequestReader(GitHubClient gitHub, List<String> allowedAccounts) {
        this.gitHub = gitHub;
        this.allowedAccounts = List.copyOf(allowedAccounts);
    }

    public boolean isGivenByAllowedAccount(int issueNumber, String label) {
        return readLabelActorsOf(issueNumber).isGivenByAllowedAccount(label);
    }

    /** Returns the account that added the label last. */
    public Optional<String> findLabelActor(int issueNumber, String label) {
        return readLabelActorsOf(issueNumber).findActor(label);
    }

    /** The events are read at the first check, and only one time for all the labels of the issue. */
    public IssueLabelActors readLabelActorsOf(int issueNumber) {
        return new IssueLabelActors(gitHub, issueNumber, this::isAllowed);
    }

    /** GitHub logins do not depend on case, so the comparison ignores case. */
    private boolean isAllowed(String login) {
        return allowedAccounts.stream().anyMatch(login::equalsIgnoreCase);
    }
}
