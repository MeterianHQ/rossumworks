package com.meterian.factory.github;

import java.util.Set;

public record Issue(int number, String title, String body, Set<String> labels, Set<String> assignees) {

    public Issue {
        assignees = Set.copyOf(assignees);
    }

    public Issue(int number, String title, String body, Set<String> labels) {
        this(number, title, body, labels, Set.of());
    }
}
