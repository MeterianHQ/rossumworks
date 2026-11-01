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

    /** GitHub logins do not depend on case, so the comparison ignores case. */
    public boolean isGivenByAllowedAccount(int issueNumber, String label) {
        return findLabelActor(issueNumber, label).filter(this::isAllowed).isPresent();
    }

    /** Returns the account that added the label last. */
    public Optional<String> findLabelActor(int issueNumber, String label) {
        return gitHub.lastLabelActor(issueNumber, label);
    }

    private boolean isAllowed(String login) {
        return allowedAccounts.stream().anyMatch(login::equalsIgnoreCase);
    }
}
