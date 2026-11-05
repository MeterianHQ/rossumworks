package com.meterian.factory.setup;

import java.util.List;

/** How a repository builds: the command, the tools of claude that run it, and the language family. */
public record RepositoryBuild(String command, List<String> allowedTools, boolean isJava) {

    public static final RepositoryBuild MAVEN = new RepositoryBuild("./mvnw verify",
            List.of("Bash(./mvnw *)", "Bash(mvn *)"), true);
    public static final RepositoryBuild GRADLE = new RepositoryBuild("./gradlew build",
            List.of("Bash(./gradlew *)"), true);
    public static final RepositoryBuild NPM = new RepositoryBuild("npm test", List.of("Bash(npm *)"), false);

    public static RepositoryBuild custom(String command, boolean isJava) {
        String firstWord = command.strip().split(" ", 2)[0];
        return new RepositoryBuild(command, List.of("Bash(" + firstWord + " *)"), isJava);
    }
}
