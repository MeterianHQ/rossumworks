package com.meterian.factory.build;

/** The result of a build. The output tail holds the last 200 lines. */
public record BuildOutcome(boolean passed, String outputTail) {
}
