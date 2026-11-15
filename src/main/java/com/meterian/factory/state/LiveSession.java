package com.meterian.factory.state;

import java.util.Optional;

/** The head commit when the live conversation opened, the Remote Control link, and the place of the conversation. */
public record LiveSession(String startHeadCommit, Optional<String> link, LivePlace place) {

    public LiveSession {
        link = link == null ? Optional.empty() : link;
        place = place == null ? LivePlace.GITHUB_COMMENT : place;
    }

    public LiveSession withLink(String newLink) {
        return new LiveSession(startHeadCommit, Optional.of(newLink), place);
    }

    public LiveSession withoutLink() {
        return new LiveSession(startHeadCommit, Optional.empty(), place);
    }

    public LiveSession withPlace(LivePlace newPlace) {
        return new LiveSession(startHeadCommit, link, newPlace);
    }
}
