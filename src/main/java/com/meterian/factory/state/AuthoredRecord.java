package com.meterian.factory.state;

/** The record of one status comment, with the id and the author of that comment. */
public record AuthoredRecord(long commentId, String authorLogin, IssueRecord record) {
}
