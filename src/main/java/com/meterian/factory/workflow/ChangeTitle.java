package com.meterian.factory.workflow;

import com.meterian.factory.github.Issue;

/** The title of the change of an issue: the commit message and the title of the pull request. */
final class ChangeTitle {

    private ChangeTitle() {
    }

    static String describe(Issue issue) {
        return "Fix #" + issue.number() + ": " + issue.title();
    }
}
