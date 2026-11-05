package com.meterian.factory.workflow;

import java.util.function.Function;

/**
 * The number of tries of the two technical retries inside an action (spec 6.4). The first try counts, so the
 * value 2 gives one try and one retry.
 *
 * @param reviewSessions the number of tries of a review round; the last failed try blocks the issue
 * @param forwardChecks  the number of tries of the check of the pushed commit on GitHub; the last failed try
 *                       blocks the issue
 */
public record RetrySettings(int reviewSessions, int forwardChecks) {

    static final String REVIEW_SESSION_PROPERTY = "factory.retries.review-session";
    static final String FORWARD_CHECK_PROPERTY = "factory.retries.forward-check";
    private static final int DEFAULT_REVIEW_SESSIONS = 2;
    private static final int DEFAULT_FORWARD_CHECKS = 3;

    public static RetrySettings defaults() {
        return new RetrySettings(DEFAULT_REVIEW_SESSIONS, DEFAULT_FORWARD_CHECKS);
    }

    /** @throws IllegalArgumentException when a property is not a whole number of tries of 1 or more */
    public static RetrySettings fromSystemProperties() {
        return fromProperties(System::getProperty);
    }

    static RetrySettings fromProperties(Function<String, String> propertyReader) {
        return new RetrySettings(
                readCount(propertyReader, REVIEW_SESSION_PROPERTY, DEFAULT_REVIEW_SESSIONS),
                readCount(propertyReader, FORWARD_CHECK_PROPERTY, DEFAULT_FORWARD_CHECKS));
    }

    private static int readCount(Function<String, String> propertyReader, String name, int defaultCount) {
        String value = propertyReader.apply(name);
        if (value == null) {
            return defaultCount;
        }
        try {
            int count = Integer.parseInt(value);
            if (count >= 1) {
                return count;
            }
        } catch (NumberFormatException exception) {
            // The message below names the property and the value.
        }
        throw new IllegalArgumentException("The system property " + name
                + " must be a whole number of 1 or more, but it is " + value + ".");
    }
}
