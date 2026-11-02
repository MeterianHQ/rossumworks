package com.meterian.factory.worker;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.workflow.IssueLabelActors;
import java.util.List;

/** Writes the note about a request label from an account that is not in accounts.allowed. */
final class IgnoredLabelNote {

    private final GitHubClient gitHub;
    private final String workerLogin;

    IgnoredLabelNote(GitHubClient gitHub, String workerLogin) {
        this.gitHub = gitHub;
        this.workerLogin = workerLogin;
    }

    /** Writes the note once: a comment of the worker with the same text means the note is there. */
    void write(IssueLabelActors labelActors, String label, List<IssueComment> comments) {
        String actor = labelActors.findActor(label).orElse("an unknown account");
        String note = "The label " + label + " from " + actor + " is ignored: the account is not in accounts.allowed.";
        boolean isNoted = comments.stream()
                .anyMatch(comment -> workerLogin.equals(comment.authorLogin()) && note.equals(comment.body()));
        if (!isNoted) {
            gitHub.createComment(labelActors.issueNumber(), note);
        }
    }
}
