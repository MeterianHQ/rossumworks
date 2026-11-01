package com.meterian.factory.github;

import java.util.Set;

public record Issue(int number, String nodeId, String title, String body, Set<String> labels) {
}
