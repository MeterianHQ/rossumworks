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

    private static final String CONTINUE_PROMPT_NAME = "continue";

    /**
     * The chosen prompt.
     *
     * @param name the name of the prompt for the event action-end: implement, continue, fix, or resume
     * @param text the text of the prompt for the agent session
     */
    record Choice(String name, String text) {
    }

    private ImplementPrompt() {
    }

    /** Empty when the findings of the pending prompt are missing. */
    static Optional<Choice> choose(PromptLibrary prompts, Issue issue, IssueRecord record, FactoryFolder folder) {
        // A new session (also after a restart without the worktree) gets the full task, not the findings.
        if (!record.agentSessionStarted()) {
            return Optional.of(chooseImplement(prompts, issue));
        }
        PendingPrompt pendingPrompt = record.pendingPrompt().orElse(PendingPrompt.IMPLEMENT);
        if (record.afterConversation()) {
            return continueWithThePendingPrompt(prompts, pendingPrompt, folder);
        }
        if (pendingPrompt == PendingPrompt.IMPLEMENT) {
            return Optional.of(chooseImplement(prompts, issue));
        }
        return renderWithTheFindings(prompts, pendingPrompt, folder)
                .map(text -> new Choice(nameOf(pendingPrompt), text));
    }

    private static Choice chooseImplement(PromptLibrary prompts, Issue issue) {
        return new Choice(nameOf(PendingPrompt.IMPLEMENT), prompts.implement(issue));
    }

    private static String nameOf(PendingPrompt pendingPrompt) {
        return pendingPrompt.name().toLowerCase();
    }

    /** A conversation in a fix must not lose the findings: the pending prompt follows the prompt continue. */
    private static Optional<Choice> continueWithThePendingPrompt(PromptLibrary prompts, PendingPrompt pendingPrompt,
            FactoryFolder folder) {
        String continuePrompt = prompts.continueAfterConversation();
        if (pendingPrompt == PendingPrompt.IMPLEMENT) {
            return Optional.of(new Choice(CONTINUE_PROMPT_NAME, continuePrompt));
        }
        return renderWithTheFindings(prompts, pendingPrompt, folder)
                .map(prompt -> new Choice(CONTINUE_PROMPT_NAME, continuePrompt + "\n\n" + prompt));
    }

    private static Optional<String> renderWithTheFindings(PromptLibrary prompts, PendingPrompt pendingPrompt,
            FactoryFolder folder) {
        return folder.readPendingFindings().map(findings -> pendingPrompt == PendingPrompt.FIX
                ? prompts.fix(findings) : prompts.resume(findings));
    }
}
