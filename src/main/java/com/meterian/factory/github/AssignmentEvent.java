package com.meterian.factory.github;

import java.time.Instant;

/** One "assigned" event of an issue. */
public record AssignmentEvent(long id, String assignee, Instant createdAt) {
}
