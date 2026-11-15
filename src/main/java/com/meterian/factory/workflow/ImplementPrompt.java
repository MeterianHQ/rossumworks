package com.meterian.factory.workflow;

import com.meterian.factory.github.Issue;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.PendingPrompt;
import java.util.Optional;

/**
 * Chooses the prompt of a coding action (spec 5.1): the full task, the prompt after a live conversation, or the
 * pending prompt with its findings.
 */
final class ImplementPrompt {

    private ImplementPrompt() {
    }

    /** Empty when the findings of the pending prompt are missing. */
    static Optional<String> choose(PromptLibrary prompts, Issue issue, IssueRecord record, FactoryFolder folder) {
        // A new session (also after a restart without the worktree) gets the full task, not the findings.
        if (!record.agentSessionStarted()) {
            return Optional.of(prompts.implement(issue));
        }
        PendingPrompt pendingPrompt = record.pendingPrompt().orElse(PendingPrompt.IMPLEMENT);
        if (record.afterConversation()) {
            return continueWithThePendingPrompt(prompts, pendingPrompt, folder);
        }
        if (pendingPrompt == PendingPrompt.IMPLEMENT) {
            return Optional.of(prompts.implement(issue));
        }
        return renderWithTheFindings(prompts, pendingPrompt, folder);
    }

    /** A conversation in a fix must not lose the findings: the pending prompt follows the prompt continue. */
    private static Optional<String> continueWithThePendingPrompt(PromptLibrary prompts, PendingPrompt pendingPrompt,
            FactoryFolder folder) {
        String continuePrompt = prompts.continueAfterConversation();
        if (pendingPrompt == PendingPrompt.IMPLEMENT) {
            return Optional.of(continuePrompt);
        }
        return renderWithTheFindings(prompts, pendingPrompt, folder).map(prompt -> continuePrompt + "\n\n" + prompt);
    }

    private static Optional<String> renderWithTheFindings(PromptLibrary prompts, PendingPrompt pendingPrompt,
            FactoryFolder folder) {
        return folder.readPendingFindings().map(findings -> pendingPrompt == PendingPrompt.FIX
                ? prompts.fix(findings) : prompts.resume(findings));
    }
}
