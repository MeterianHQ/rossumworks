package com.meterian.factory.setup;

/** The create command cannot go on. The message tells the owner what to do. */
public final class SetupException extends Exception {

    public SetupException(String message) {
        super(message);
    }
}
