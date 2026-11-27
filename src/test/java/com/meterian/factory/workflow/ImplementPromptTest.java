package com.meterian.factory.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.github.Issue;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.prompt.PromptTexts;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.PendingPrompt;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImplementPromptTest {

    private static final Issue ISSUE = new Issue(7, "Change the greeting", "The greeting must change.", Set.of());
    private static final IssueRecord STARTED_RECORD = IssueRecord.queued(7, "implement").withAgentSessionStarted();

    private final PromptLibrary prompts = new PromptLibrary(createTextsWithResume(), "main");
    private FactoryFolder folder;

    /** The built-in definition has no prompt resume, so the test gives one. */
    private static PromptTexts createTextsWithResume() {
        PromptTexts texts = PromptTexts.from(BuiltInDefinition.load());
        return new PromptTexts(texts.implement(), texts.fix(), texts.continueAfterConversation(),
                Optional.of("Resume the work.\n\n{{findings}}"), texts.reviews(), texts.risk());
    }

    @BeforeEach
    void createFolderWithFindings(@TempDir Path worktree) {
        folder = new FactoryFolder(worktree);
        folder.writePendingFindings(List.of(new Finding("The input check is missing.", "Check the input.")));
    }

    @Test
    void namesTheImplementPromptOfANewSession() {
        IssueRecord record = IssueRecord.queued(7, "implement").withPendingPrompt(PendingPrompt.FIX);

        assertThat(choose(record).name()).isEqualTo("implement");
    }

    @Test
    void namesTheImplementPromptOfAStartedSession() {
        assertThat(choose(STARTED_RECORD).name()).isEqualTo("implement");
    }

    @Test
    void namesTheFixPrompt() {
        assertThat(choose(STARTED_RECORD.withPendingPrompt(PendingPrompt.FIX)).name()).isEqualTo("fix");
    }

    @Test
    void namesTheResumePrompt() {
        assertThat(choose(STARTED_RECORD.withPendingPrompt(PendingPrompt.RESUME)).name()).isEqualTo("resume");
    }

    @Test
    void namesTheContinuePromptAfterAConversation() {
        ImplementPrompt.Choice choice = choose(STARTED_RECORD.withAfterConversation());

        assertThat(choice.name()).isEqualTo("continue");
        assertThat(choice.text()).isEqualTo(prompts.continueAfterConversation());
    }

    @Test
    void namesTheContinuePromptAfterAConversationInAFix() {
        ImplementPrompt.Choice choice =
                choose(STARTED_RECORD.withPendingPrompt(PendingPrompt.FIX).withAfterConversation());

        assertThat(choice.name()).isEqualTo("continue");
        assertThat(choice.text()).startsWith(prompts.continueAfterConversation()).contains("Check the input.");
    }

    private ImplementPrompt.Choice choose(IssueRecord record) {
        return ImplementPrompt.choose(prompts, ISSUE, record, folder).orElseThrow();
    }
}
