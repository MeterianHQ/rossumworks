package com.meterian.factory.workflow;

import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.state.ActionType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** The actions of one definition version, with the helpers that depend on the version, for example the merge target. */
final class ActionSet {

    private final FactoryDefinition definition;
    private final VersionServices version;
    private final Map<ActionType, Action> actions = new EnumMap<>(ActionType.class);
    private final Action waitAction;

    private ActionSet(FactoryDefinition definition, VersionServices version, Action waitAction) {
        this.definition = definition;
        this.version = version;
        this.waitAction = waitAction;
    }

    static ActionSet create(FactoryDefinition definition, ActionServices services) {
        ActionSet actionSet = new ActionSet(definition, VersionServices.create(definition, services),
                new WaitAction(services.liveSessions()));
        VersionServices version = actionSet.version;
        ReviewSessionRetry retry = new ReviewSessionRetry(services.retrySettings().reviewSessions());
        actionSet.actions.put(ActionType.PREPARE, new PrepareAction(version, definition.workflow()));
        actionSet.actions.put(ActionType.CODING, new CodingAction(services, version));
        actionSet.actions.put(ActionType.BUILD, new BuildAction(services, version));
        actionSet.actions.put(ActionType.REVIEWS, new ReviewsAction(services, version, retry));
        actionSet.actions.put(ActionType.RISK, new RiskAction(services, version, retry));
        actionSet.actions.put(ActionType.MERGE_GATE, new MergeGateAction(services, version, definition));
        actionSet.actions.put(ActionType.APPROVAL, new ApprovalAction(services.git(), services.ownerApproval(),
                new IssueTextCheck(services.ownerApproval()), definition));
        actionSet.actions.put(ActionType.CONVERSATION, new ConversationAction(services, version, definition));
        actionSet.actions.put(ActionType.FINISH, new FinishAction(services, version));
        return actionSet;
    }

    /** A state without an action waits for a continue request. */
    Action actionOf(Optional<ActionType> type) {
        return type.map(actions::get).orElse(waitAction);
    }

    FactoryDefinition definition() {
        return definition;
    }

    PullRequestPublisher pullRequestPublisher() {
        return version.pullRequestPublisher();
    }
}
