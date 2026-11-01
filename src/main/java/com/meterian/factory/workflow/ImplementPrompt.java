package com.meterian.factory.workflow;

import com.meterian.factory.github.Issue;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.state.IssueRecord;
import java.util.Optional;

/** Chooses the prompt of an implement step: the full task, the prompt after a live conversation, or the findings. */
final class ImplementPrompt {

    private ImplementPrompt() {
    }

    /** Empty when the findings of the fix loop are missing. */
    static Optional<String> choose(PromptLibrary prompts, Issue issue, IssueRecord record, FactoryFolder folder) {
        // A new session (also after a restart without the worktree) gets the full task, not the findings.
        if (!record.agentSessionStarted()) {
            return Optional.of(prompts.implement(issue));
        }
        if (record.afterConversation()) {
            return Optional.of(continueWithThePendingFix(prompts, folder));
        }
        if (record.fixLoops() == 0) {
            return Optional.of(prompts.implement(issue));
        }
        return folder.readFixPrompt();
    }

    /** A conversation during a fix loop must not lose the findings: the fix prompt follows the continue prompt. */
    private static String continueWithThePendingFix(PromptLibrary prompts, FactoryFolder folder) {
        String continuePrompt = prompts.continueAfterConversation();
        return folder.readFixPrompt().map(fixPrompt -> continuePrompt + "\n\n" + fixPrompt).orElse(continuePrompt);
    }
}
