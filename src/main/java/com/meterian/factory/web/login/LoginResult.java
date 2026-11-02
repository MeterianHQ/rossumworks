package com.meterian.factory.web.login;

import java.time.Instant;
import java.util.Optional;

/**
 * The result of one login attempt.
 *
 * @param outcome the decision
 * @param sessionToken the new session token, only for ACCEPTED
 * @param retryAfter the end of the lock, only for LOCKED
 */
public record LoginResult(Outcome outcome, Optional<String> sessionToken, Optional<Instant> retryAfter) {

    public enum Outcome { ACCEPTED, WRONG_PASSWORD, LOCKED }

    public static LoginResult accepted(String sessionToken) {
        return new LoginResult(Outcome.ACCEPTED, Optional.of(sessionToken), Optional.empty());
    }

    public static LoginResult wrongPassword() {
        return new LoginResult(Outcome.WRONG_PASSWORD, Optional.empty(), Optional.empty());
    }

    public static LoginResult locked(Instant retryAfter) {
        return new LoginResult(Outcome.LOCKED, Optional.empty(), Optional.of(retryAfter));
    }
}
