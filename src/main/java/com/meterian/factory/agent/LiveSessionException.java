package com.meterian.factory.agent;

/** The live session of an issue cannot open, or a tmux command fails. */
public class LiveSessionException extends Exception {

    public LiveSessionException(String message) {
        super(message);
    }

    public LiveSessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
