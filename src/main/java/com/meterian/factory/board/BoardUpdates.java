package com.meterian.factory.board;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import java.util.List;

/** The way the worker tells the board what it sees. */
public interface BoardUpdates {

    /** Does nothing. Only the tests that do not look at the board use it: Main always gives a BoardState. */
    BoardUpdates IGNORED = new BoardUpdates() {
        @Override
        public void recordPoll(List<Issue> labeledIssues) {
        }

        @Override
        public void recordIssue(Issue issue, IssueRecord record) {
        }
    };

    void recordPoll(List<Issue> labeledIssues);

    void recordIssue(Issue issue, IssueRecord record);
}
