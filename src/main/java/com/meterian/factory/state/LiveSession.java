package com.meterian.factory.state;

import java.util.Optional;

/** The state of the issue just before it entered the live conversation, and the Remote Control link. */
public record LiveSession(
        String startHeadCommit,
        IssueStatus statusBefore,
        Optional<WaitReason> waitBefore,
        Optional<String> link) {

    public LiveSession {
        waitBefore = waitBefore == null ? Optional.empty() : waitBefore;
        link = link == null ? Optional.empty() : link;
    }

    public LiveSession withLink(String newLink) {
        return new LiveSession(startHeadCommit, statusBefore, waitBefore, Optional.of(newLink));
    }

    public LiveSession withoutLink() {
        return new LiveSession(startHeadCommit, statusBefore, waitBefore, Optional.empty());
    }
}
