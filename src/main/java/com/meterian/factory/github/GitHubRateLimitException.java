package com.meterian.factory.github;

import java.time.Instant;

/** GitHub refuses more requests until the reset time. The message never holds the token. */
public final class GitHubRateLimitException extends GitHubException {

    private final Instant resetTime;

    public GitHubRateLimitException(String message, Instant resetTime) {
        super(message);
        this.resetTime = resetTime;
    }

    public Instant resetTime() {
        return resetTime;
    }
}
