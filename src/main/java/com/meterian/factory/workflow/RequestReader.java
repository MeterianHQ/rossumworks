package com.meterian.factory.workflow;

import com.meterian.factory.github.GitHubClient;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** Tells who gave a request label. Fails closed: a label with no event is not a request. */
public final class RequestReader {

    private final GitHubClient gitHub;
    private final List<String> allowedAccounts;
    private final String workerLogin;
    private final PageRequests pageRequests;

    /** A reader with no worker account: only an allowed account gives a request. */
    public RequestReader(GitHubClient gitHub, List<String> allowedAccounts) {
        this(gitHub, allowedAccounts, "", new PageRequests(Clock.systemUTC()));
    }

    public RequestReader(GitHubClient gitHub, List<String> allowedAccounts, String workerLogin,
            PageRequests pageRequests) {
        this.gitHub = gitHub;
        this.allowedAccounts = List.copyOf(allowedAccounts);
        this.workerLogin = workerLogin;
        this.pageRequests = pageRequests;
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
        return new IssueLabelActors(gitHub, issueNumber,
                (label, actor) -> isAcceptedActor(issueNumber, label, actor));
    }

    /** An allowed account gives a request. The worker account gives a request only when the page remembers it. */
    private boolean isAcceptedActor(int issueNumber, String label, String actor) {
        return isAllowed(actor) || isRememberedPageRequest(actor, issueNumber, label);
    }

    private boolean isRememberedPageRequest(String actor, int issueNumber, String label) {
        return !workerLogin.isEmpty() && actor.equalsIgnoreCase(workerLogin)
                && pageRequests.isRemembered(issueNumber, label);
    }

    /** GitHub logins do not depend on case, so the comparison ignores case. */
    private boolean isAllowed(String login) {
        return allowedAccounts.stream().anyMatch(login::equalsIgnoreCase);
    }
}
