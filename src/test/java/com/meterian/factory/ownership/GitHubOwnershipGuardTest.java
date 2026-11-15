package com.meterian.factory.ownership;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.meterian.factory.testing.FakeGitHubClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubOwnershipGuardTest {

    private static final int ISSUE_NUMBER = 7;

    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private final OwnershipGuard guard = new GitHubOwnershipGuard(gitHub, "worker");

    @BeforeEach
    void createIssue() {
        gitHub.addIssue(ISSUE_NUMBER, "title", "body");
    }

    @Test
    void guardAcceptsTheOwner() {
        gitHub.assignBy(ISSUE_NUMBER, "worker");

        assertDoesNotThrow(() -> guard.verifyStillOwner(ISSUE_NUMBER));
    }

    @Test
    void guardAcceptsAnIssueWithoutAssignee() {
        assertDoesNotThrow(() -> guard.verifyStillOwner(ISSUE_NUMBER));
    }

    @Test
    void guardRefusesAnotherOwner() {
        gitHub.assignBy(ISSUE_NUMBER, "alice");

        OwnershipLostException exception =
                assertThrows(OwnershipLostException.class, () -> guard.verifyStillOwner(ISSUE_NUMBER));
        assertEquals(ISSUE_NUMBER, exception.issueNumber());
    }
}
