package com.meterian.factory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.github.Issue;
import com.meterian.factory.web.PageAction;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The worker must see the label that each button of the page adds. */
class PageButtonLabelTest {

    @Test
    void theWorkerSeesTheEnterRequestOfTheTerminalButton() {
        assertThat(EnterRequests.isRequested(issueWithTheLabelOf(PageAction.ENTER_TERMINAL))).isTrue();
    }

    @Test
    void theWorkerSeesTheEnterRequestOfTheBrowserButton() {
        assertThat(EnterRequests.isRequested(issueWithTheLabelOf(PageAction.ENTER_BROWSER))).isTrue();
    }

    @Test
    void theWorkerSeesTheRequestOfTheStartAgainButton() {
        assertThat(StartAgainRequests.isRequested(issueWithTheLabelOf(PageAction.START_AGAIN))).isTrue();
    }

    @Test
    void theWorkerSeesNoEnterRequestOnAnIssueWithoutTheLabel() {
        assertThat(EnterRequests.isRequested(issueWithTheLabelOf(PageAction.APPROVE))).isFalse();
    }

    private static Issue issueWithTheLabelOf(PageAction action) {
        return new Issue(12, "Title", "body", Set.of("factory", action.label().orElseThrow()));
    }
}
