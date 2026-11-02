package com.meterian.factory.web.login;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Counts the wrong passwords in a row and locks the login for the worker. The caller synchronizes the access. */
final class WrongPasswordLock {

    private final int maximumWrongPasswords;
    private final Duration lockDuration;
    private int wrongPasswordsInARow;
    private Instant lockedUntil;

    WrongPasswordLock(int maximumWrongPasswords, Duration lockDuration) {
        this.maximumWrongPasswords = maximumWrongPasswords;
        this.lockDuration = lockDuration;
    }

    /** Gives the end of the lock, if the lock is active now. */
    Optional<Instant> findActiveLock(Instant now) {
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            return Optional.of(lockedUntil);
        }
        return Optional.empty();
    }

    /** Counts a wrong password. Gives the end of the new lock, if this password starts one. */
    Optional<Instant> recordWrongPassword(Instant now) {
        wrongPasswordsInARow++;
        if (wrongPasswordsInARow < maximumWrongPasswords) {
            return Optional.empty();
        }
        wrongPasswordsInARow = 0;
        lockedUntil = now.plus(lockDuration);
        return Optional.of(lockedUntil);
    }

    void recordCorrectPassword() {
        wrongPasswordsInARow = 0;
        lockedUntil = null;
    }
}
