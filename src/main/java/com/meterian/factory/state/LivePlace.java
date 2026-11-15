package com.meterian.factory.state;

import com.fasterxml.jackson.annotation.JsonAlias;

/** The place where the person has the live conversation. */
public enum LivePlace {
    TERMINAL,
    BROWSER,
    /** An Enter from the GitHub app: the link is only in a comment of the issue. An older record says PHONE. */
    @JsonAlias("PHONE")
    GITHUB_COMMENT
}
