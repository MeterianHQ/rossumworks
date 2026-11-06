package com.meterian.factory.setup;

import java.util.Optional;
import java.util.function.Predicate;

/** Finds the build of a repository from the files in its root folder. */
public final class BuildFinder {

    private BuildFinder() {
    }

    public static Optional<RepositoryBuild> find(Predicate<String> hasFile) {
        if (hasFile.test("mvnw")) {
            return Optional.of(RepositoryBuild.MAVEN);
        }
        if (hasFile.test("gradlew")) {
            return Optional.of(RepositoryBuild.GRADLE);
        }
        if (hasFile.test("package.json")) {
            return Optional.of(RepositoryBuild.NPM);
        }
        return Optional.empty();
    }

    public static boolean isJavaRepository(Predicate<String> hasFile) {
        return hasFile.test("mvnw") || hasFile.test("gradlew") || hasFile.test("pom.xml");
    }
}
