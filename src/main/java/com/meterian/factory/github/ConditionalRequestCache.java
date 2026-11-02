package com.meterian.factory.github;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The last answer with an ETag for each list page, by path and query. A 304 answer reuses the cached body.
 * GitHub does not count a 304 answer against the rate limit. The cache keeps only the most recent pages.
 */
final class ConditionalRequestCache {

    /** One page of a list: the body, the ETag, and the Link header to the next page. */
    record CachedPage(String entityTag, String body, Optional<String> link) {
    }

    private final Map<String, CachedPage> pages;

    ConditionalRequestCache(int pageLimit) {
        this.pages = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedPage> eldest) {
                return size() > pageLimit;
            }
        };
    }

    synchronized Optional<CachedPage> find(String pathAndQuery) {
        return Optional.ofNullable(pages.get(pathAndQuery));
    }

    synchronized void store(String pathAndQuery, CachedPage page) {
        pages.put(pathAndQuery, page);
    }
}
