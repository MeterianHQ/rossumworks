package com.meterian.factory.web.login;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** The sessions in memory, from the oldest to the newest. It is thread safe. */
final class SessionStore {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random;
    private final int maximumSessions;
    private final Map<String, Instant> expiryByToken = new LinkedHashMap<>();

    SessionStore(SecureRandom random, int maximumSessions) {
        this.random = random;
        this.maximumSessions = maximumSessions;
    }

    /** Makes a new session and removes the oldest sessions above the maximum. */
    synchronized String createSession(Instant now, Instant expiry) {
        removeExpiredSessions(now);
        while (expiryByToken.size() >= maximumSessions) {
            removeOldestSession();
        }
        String token = createToken();
        expiryByToken.put(token, expiry);
        return token;
    }

    /** Finds the exact token. A session is valid before its expiry. */
    synchronized boolean isValidSession(String token, Instant now) {
        Instant expiry = expiryByToken.get(token);
        if (expiry == null) {
            return false;
        }
        if (now.isBefore(expiry)) {
            return true;
        }
        expiryByToken.remove(token);
        return false;
    }

    private void removeExpiredSessions(Instant now) {
        expiryByToken.values().removeIf(expiry -> !now.isBefore(expiry));
    }

    private void removeOldestSession() {
        Iterator<String> tokens = expiryByToken.keySet().iterator();
        tokens.next();
        tokens.remove();
    }

    private String createToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
