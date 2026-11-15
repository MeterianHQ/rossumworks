package com.meterian.factory.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.meterian.factory.github.Issue;
import com.meterian.factory.testing.FakeGitHubClient;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IssueClaimTest {

    private static final int ISSUE_NUMBER = 7;

    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private final IssueClaim claim = new IssueClaim(gitHub, "worker");

    @BeforeEach
    void createIssue() {
        gitHub.addIssue(ISSUE_NUMBER, "title", "body");
    }

    @Test
    void claimAssignsAnIssueWithoutAssignee() {
        assertEquals(Ownership.OWNED, claim.claim(gitHub.issue(ISSUE_NUMBER)));
        assertEquals(Set.of("worker"), gitHub.assigneesOf(ISSUE_NUMBER));
    }

    @Test
    void claimKeepsAnIssueOfTheWorker() {
        gitHub.assignBy(ISSUE_NUMBER, "worker");
        int eventsBefore = gitHub.assignmentEvents(ISSUE_NUMBER).size();

        assertEquals(Ownership.OWNED, claim.claim(gitHub.issue(ISSUE_NUMBER)));
        assertEquals(eventsBefore, gitHub.assignmentEvents(ISSUE_NUMBER).size());
    }

    @Test
    void claimLeavesAnIssueOfAnotherAccount() {
        gitHub.assignBy(ISSUE_NUMBER, "alice");

        assertEquals(Ownership.OWNED_BY_ANOTHER, claim.claim(gitHub.issue(ISSUE_NUMBER)));
        assertEquals(Set.of("alice"), gitHub.assigneesOf(ISSUE_NUMBER));
    }

    @Test
    void claimLosesTheRaceAndRemovesItself() {
        Issue issueOfThePoll = gitHub.issue(ISSUE_NUMBER);
        gitHub.assignBy(ISSUE_NUMBER, "alice");

        assertEquals(Ownership.OWNED_BY_ANOTHER, claim.claim(issueOfThePoll));
        assertEquals(Set.of("alice"), gitHub.assigneesOf(ISSUE_NUMBER));
    }

    @Test
    void claimWinsTheRaceAndKeepsTheIssue() {
        gitHub.whenAssigning(() -> gitHub.assignBy(ISSUE_NUMBER, "alice"));

        assertEquals(Ownership.OWNED, claim.claim(gitHub.issue(ISSUE_NUMBER)));
        assertEquals(Set.of("worker", "alice"), gitHub.assigneesOf(ISSUE_NUMBER));
    }

    @Test
    void claimRemovesTheWorkerWhenAnEarlierAssigneeOwnsTheIssue() {
        gitHub.assignBy(ISSUE_NUMBER, "alice");
        gitHub.assignBy(ISSUE_NUMBER, "worker");

        assertEquals(Ownership.OWNED_BY_ANOTHER, claim.claim(gitHub.issue(ISSUE_NUMBER)));
        assertEquals(Set.of("alice"), gitHub.assigneesOf(ISSUE_NUMBER));
    }

    @Test
    void claimRefusesAnIssueWhenGitHubIgnoresTheAssignee() {
        gitHub.ignoreTheAssignees();

        assertEquals(Ownership.UNASSIGNED, claim.claim(gitHub.issue(ISSUE_NUMBER)));
    }
}
