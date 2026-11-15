package com.meterian.factory.web.login;

import java.util.Arrays;
import java.util.Optional;

/** The rules of a password for the web page. */
public final class PasswordRules {

    public static final int MINIMUM_LENGTH = 12;

    private PasswordRules() {
    }

    /** Returns the message of the first broken rule, or empty when the password is valid. */
    public static Optional<String> verify(char[] password, char[] repeated) {
        if (!Arrays.equals(password, repeated)) {
            return Optional.of("The two passwords are not the same.");
        }
        if (password.length < MINIMUM_LENGTH) {
            return Optional.of("The password must have " + MINIMUM_LENGTH + " characters or more.");
        }
        return Optional.empty();
    }
}
