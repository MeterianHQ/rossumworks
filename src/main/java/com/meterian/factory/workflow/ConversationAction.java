package com.meterian.factory.workflow;

import com.meterian.factory.agent.LiveSessionException;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.git.GitException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.LiveSession;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The live conversation (spec 5.0). The action opens the conversation when the issue enters the state, and it ends
 * when the owner types /factory:continue: with changed when the conversation changed the code, else with continue.
 */
final class ConversationAction implements Action {

    static final String NO_CHANGE_NOTE = "The live conversation ended with no change to the code.";
    private static final String NOT_OPEN_REASON = "The live conversation did not open: ";

    private final ActionServices services;
    private final VersionServices version;
    private final FactoryDefinition definition;

    ConversationAction(ActionServices services, VersionServices version, FactoryDefinition definition) {
        this.services = services;
        this.version = version;
        this.definition = definition;
    }

    /** A live state without an open session opens again: Enter after a session that ended. */
    @Override
    public ActionResult run(Issue issue, IssueRecord record, Path worktree) {
        LiveMode liveMode = services.liveMode();
        if (record.live().isPresent() && liveMode.isContinueRequested(record)) {
            return endTheConversation(issue, record, worktree);
        }
        if (liveMode.findOpenLink(record).isEmpty()) {
            return open(record, worktree);
        }
        return ActionResult.stay(record);
    }

    private ActionResult open(IssueRecord record, Path worktree) {
        try {
            return ActionResult.stay(services.liveMode().open(record, worktree, version.allowedTools()));
        } catch (LiveSessionException | GitException exception) {
            return ActionResult.block(record, NOT_OPEN_REASON + exception.getMessage());
        }
    }

    /** An approval covers one diff, and the results of the old files are old: a change of the code removes them. */
    private ActionResult endTheConversation(Issue issue, IssueRecord record, Path worktree) {
        LiveSession live = record.live().orElseThrow();
        services.liveMode().close(record, worktree);
        IssueRecord closed = record.withoutLive();
        if (!services.liveMode().hasChangedInLiveMode(live, isAfterCodingAction(record), worktree)) {
            return ActionResult.endWithReason(closed, "continue", NO_CHANGE_NOTE);
        }
        if (services.git().hasUncommittedChanges(worktree)) {
            services.git().commitAll(worktree, ChangeTitle.describe(issue));
        }
        services.ownerApproval().withdraw(record.issueNumber());
        return ActionResult.endWith(closed.withoutReviews().withoutRisk().withoutBuiltTree().withoutApprovedTree(), "changed");
    }

    private boolean isAfterCodingAction(IssueRecord record) {
        return record.previousState()
                .map(definition.workflow()::state)
                .flatMap(StateDefinition::actionType)
                .equals(Optional.of(ActionType.CODING));
    }
}
