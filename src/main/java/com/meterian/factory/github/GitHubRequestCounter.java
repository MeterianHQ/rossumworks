package com.meterian.factory.github;

import java.net.URI;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Counts the requests to GitHub by method and path template, for example GET /repos/{o}/{r}/issues/{n}. */
final class GitHubRequestCounter {

    private static final Pattern NUMBER_SEGMENT = Pattern.compile("/\\d+(?=/|$)");

    private final String repositoryPath;
    private final Map<String, Integer> counts = new TreeMap<>();

    GitHubRequestCounter(String repositoryPath) {
        this.repositoryPath = repositoryPath;
    }

    /** A 304 answer has its own key, because GitHub does not count it against the rate limit. */
    synchronized void count(String method, URI uri, boolean isNotModified) {
        String key = method + " " + toPathTemplate(uri.getRawPath()) + (isNotModified ? " (304)" : "");
        counts.merge(key, 1, Integer::sum);
    }

    synchronized Map<String, Integer> counts() {
        return Map.copyOf(counts);
    }

    /** Reads and resets in one step, so that a request of another thread is never lost between them. */
    synchronized Map<String, Integer> take() {
        Map<String, Integer> taken = Map.copyOf(counts);
        counts.clear();
        return taken;
    }

    synchronized void reset() {
        counts.clear();
    }

    /** The query is not part of the template: all the pages of one list count under one path. */
    private String toPathTemplate(String path) {
        String withoutRepository = path.startsWith(repositoryPath + "/") || path.equals(repositoryPath)
                ? "/repos/{o}/{r}" + path.substring(repositoryPath.length()) : path;
        return NUMBER_SEGMENT.matcher(withoutRepository).replaceAll("/{n}");
    }
}
