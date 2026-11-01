package com.meterian.factory.worker;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The log line with the GitHub requests of one poll: the total and the largest counts by path template. */
final class PollRequestSummary {

    /** More counts make the line too long to read in a log. */
    static final int MAXIMUM_SHOWN_COUNTS = 10;

    private static final Comparator<Map.Entry<String, Integer>> LARGEST_FIRST =
            Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey());

    private PollRequestSummary() {
    }

    static String describe(Map<String, Integer> counts) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        String line = "The poll sent " + total + " GitHub requests";
        if (counts.isEmpty()) {
            return line + ".";
        }
        List<String> shown = counts.entrySet().stream().sorted(LARGEST_FIRST).limit(MAXIMUM_SHOWN_COUNTS)
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.toList());
        int hidden = counts.size() - shown.size();
        if (hidden > 0) {
            shown.add("and " + hidden + " more paths");
        }
        return line + ": " + String.join(", ", shown) + ".";
    }
}
