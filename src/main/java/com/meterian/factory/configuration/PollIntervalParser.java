package com.meterian.factory.configuration;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a duration in the form {@code <number>s} or {@code <number>m}. */
final class PollIntervalParser {

    private static final Pattern FORMAT = Pattern.compile("(\\d+)([sm])");

    private PollIntervalParser() {
    }

    static Duration parse(String text) {
        Matcher matcher = FORMAT.matcher(text.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Use <number>s or <number>m, for example 60s.");
        }
        long amount = Long.parseLong(matcher.group(1));
        return "m".equals(matcher.group(2)) ? Duration.ofMinutes(amount) : Duration.ofSeconds(amount);
    }
}
