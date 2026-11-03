package com.meterian.factory.workflow;

import com.meterian.factory.definition.LoopDefinition;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.List;

/** Sends findings back to the agent in charge, up to the limit of the loop. */
final class FixLoop {

    private final PromptLibrary prompts;
    private final OwnerApproval ownerApproval;

    FixLoop(PromptLibrary prompts, OwnerApproval ownerApproval) {
        this.prompts = prompts;
        this.ownerApproval = ownerApproval;
    }

    IssueRecord countEntry(IssueRecord record) {
        return record.withFixLoop();
    }

    boolean isAboveLimit(IssueRecord counted, LoopDefinition loop) {
        return counted.fixLoops() > loop.limit();
    }

    /** Writes the fix prompt for the next agent step. An approval is for one change, so it goes away. */
    void sendFindingsBack(int issueNumber, Path worktree, List<Finding> findings) {
        new FactoryFolder(worktree).writeFixPrompt(prompts.fix(findings));
        ownerApproval.withdraw(issueNumber);
    }
}
