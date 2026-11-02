package com.meterian.factory.workflow;

import com.meterian.factory.github.GitHubClient;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * The actors of the request labels of one issue. The first check reads the events, and the other checks use the
 * same read. Use one instance for one check of an issue only, so that a later label event is never missed.
 */
public final class IssueLabelActors {

    private final GitHubClient gitHub;
    private final int issueNumber;
    private final BiPredicate<String, String> isAcceptedActorOfLabel;
    private Map<String, String> actorsByLabel;

    /** The rule gets the label first and the actor second. */
    IssueLabelActors(GitHubClient gitHub, int issueNumber, BiPredicate<String, String> isAcceptedActorOfLabel) {
        this.gitHub = gitHub;
        this.issueNumber = issueNumber;
        this.isAcceptedActorOfLabel = isAcceptedActorOfLabel;
    }

    public int issueNumber() {
        return issueNumber;
    }

    /** Returns the account that added the label last. */
    public synchronized Optional<String> findActor(String label) {
        if (actorsByLabel == null) {
            actorsByLabel = gitHub.lastLabelActors(issueNumber);
        }
        return Optional.ofNullable(actorsByLabel.get(label));
    }

    public boolean isGivenByAllowedAccount(String label) {
        return findActor(label).filter(actor -> isAcceptedActorOfLabel.test(label, actor)).isPresent();
    }
}
