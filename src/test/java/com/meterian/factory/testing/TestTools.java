package com.meterian.factory.testing;

import java.util.List;

/** The allowed tools that the tests give to an agent. They equal the tools of the built-in definition. */
public final class TestTools {

    public static final List<String> ALLOWED_TOOLS = List.of(
            "Read", "Edit", "Write", "Glob", "Grep",
            "Bash(git status*)", "Bash(git diff*)", "Bash(git log*)", "Bash(git show*)",
            "Bash(./mvnw *)", "Bash(mvn *)");

    private TestTools() {
    }
}
