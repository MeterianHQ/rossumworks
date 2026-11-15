package com.meterian.factory.ownership;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;

/** Claims an issue for the worker account with the GitHub assignee. */
public final class IssueClaim {

    private static final System.Logger LOGGER = System.getLogger(IssueClaim.class.getName());

    private final GitHubClient gitHub;
    private final String workerLogin;

    public IssueClaim(GitHubClient gitHub, String workerLogin) {
        this.gitHub = gitHub;
        this.workerLogin = workerLogin;
    }

    /** The caller works on the issue only when the result is OWNED. */
    public Ownership claim(Issue issue) {
        Ownership ownership = decide(issue);
        if (ownership != Ownership.UNASSIGNED) {
            return ownership;
        }
        gitHub.addAssignee(issue.number(), workerLogin);
        return settleClaim(issue.number());
    }

    private Ownership settleClaim(int issueNumber) {
        Ownership ownership = decide(gitHub.issue(issueNumber));
        if (ownership == Ownership.OWNED_BY_ANOTHER) {
            gitHub.removeAssignee(issueNumber, workerLogin);
        } else if (ownership == Ownership.UNASSIGNED) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "GitHub ignored the assignee {1} on the issue {0}", issueNumber, workerLogin);
        }
        return ownership;
    }

    private Ownership decide(Issue issue) {
        return OwnershipRule.decide(issue, workerLogin, () -> gitHub.assignmentEvents(issue.number()));
    }
}
