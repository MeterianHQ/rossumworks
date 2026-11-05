package com.meterian.factory.setup;

import java.util.Optional;

/** The talk with the owner. A test gives a fake, so that it never uses the real console. */
public interface SetupConsole {

    boolean isInteractive();

    /** Asks a question. An empty answer gives the default answer. */
    String ask(String question, String defaultAnswer);

    /** Reads a password without echo. */
    Optional<char[]> readPassword(String prompt);

    /** Asks a yes or no question. An empty answer is no. */
    boolean confirm(String question);

    void show(String line);
}
