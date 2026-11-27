package com.meterian.factory.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.testing.MutableClock;
import com.meterian.factory.web.PageAction;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The workflow must see the label that each button of the page adds. */
class PageButtonLabelTest {

    private static final int ISSUE_NUMBER = 12;

    @Test
    void theOwnerApprovalSeesTheLabelOfTheApproveButton() {
        Issue issue = new Issue(ISSUE_NUMBER, "Title", "body", Set.of("factory", labelOf(PageAction.APPROVE)));

        assertThat(new OwnerApproval(null).isGiven(issue)).isTrue();
    }

    @Test
    void thePageRequestsTreatTheLabelOfTheBrowserButtonAsAnEnter() {
        PageRequests pageRequests = new PageRequests(new MutableClock(Instant.parse("2026-11-01T10:00:00Z")));

        pageRequests.rememberAcceptedRequest(ISSUE_NUMBER, labelOf(PageAction.ENTER_BROWSER));

        assertThat(pageRequests.findEnterPlace(ISSUE_NUMBER)).contains(LivePlace.GITHUB_COMMENT);
    }

    private static String labelOf(PageAction action) {
        return action.label().orElseThrow();
    }
}
