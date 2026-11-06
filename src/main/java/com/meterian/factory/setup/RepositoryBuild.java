package com.meterian.factory.setup;

import java.util.List;

/** How a repository builds: the command, the tools of claude that run it, and the language family. */
public record RepositoryBuild(String command, List<String> allowedTools, boolean isJava) {

    public static final RepositoryBuild MAVEN = new RepositoryBuild("./mvnw verify",
            List.of("Bash(./mvnw *)", "Bash(mvn *)"), true);
    public static final RepositoryBuild GRADLE = new RepositoryBuild("./gradlew build",
            List.of("Bash(./gradlew *)"), true);
    public static final RepositoryBuild NPM = new RepositoryBuild("npm test", List.of("Bash(npm *)"), false);

    /** A build with the command of the owner. Claude can run the first word of the command. */
    public static RepositoryBuild custom(String command, boolean isJava) {
        if (command.isBlank()) {
            throw new IllegalArgumentException("The build command is blank.");
        }
        String strippedCommand = command.strip();
        String firstWord = strippedCommand.split("\\s+", 2)[0];
        return new RepositoryBuild(strippedCommand, List.of("Bash(" + firstWord + " *)"), isJava);
    }
}
