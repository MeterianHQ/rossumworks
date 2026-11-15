package com.meterian.factory.state;

import java.util.Optional;

/** The state of the issue just before it entered the live conversation, and the Remote Control link. */
public record LiveSession(
        String startHeadCommit,
        IssueStatus statusBefore,
        Optional<WaitReason> waitBefore,
        Optional<String> link,
        LivePlace place) {

    public LiveSession {
        waitBefore = waitBefore == null ? Optional.empty() : waitBefore;
        link = link == null ? Optional.empty() : link;
        place = place == null ? LivePlace.GITHUB_COMMENT : place;
    }

    public LiveSession withLink(String newLink) {
        return new LiveSession(startHeadCommit, statusBefore, waitBefore, Optional.of(newLink), place);
    }

    public LiveSession withoutLink() {
        return new LiveSession(startHeadCommit, statusBefore, waitBefore, Optional.empty(), place);
    }

    public LiveSession withPlace(LivePlace newPlace) {
        return new LiveSession(startHeadCommit, statusBefore, waitBefore, link, newPlace);
    }
}
