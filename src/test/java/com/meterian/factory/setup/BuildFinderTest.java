package com.meterian.factory.setup;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BuildFinderTest {

    private static Optional<RepositoryBuild> findWith(String... files) {
        return BuildFinder.find(Set.of(files)::contains);
    }

    @Test
    void findsMaven() {
        RepositoryBuild build = findWith("mvnw").orElseThrow();
        assertThat(build.command()).isEqualTo("./mvnw verify");
        assertThat(build.allowedTools()).containsExactly("Bash(./mvnw *)", "Bash(mvn *)");
        assertThat(build.isJava()).isTrue();
    }

    @Test
    void findsGradle() {
        RepositoryBuild build = findWith("gradlew").orElseThrow();
        assertThat(build.command()).isEqualTo("./gradlew build");
        assertThat(build.allowedTools()).containsExactly("Bash(./gradlew *)");
        assertThat(build.isJava()).isTrue();
    }

    @Test
    void findsNpm() {
        RepositoryBuild build = findWith("package.json").orElseThrow();
        assertThat(build.command()).isEqualTo("npm test");
        assertThat(build.allowedTools()).containsExactly("Bash(npm *)");
        assertThat(build.isJava()).isFalse();
    }

    @Test
    void findsMavenBeforeNpm() {
        assertThat(findWith("package.json", "mvnw").orElseThrow().command()).isEqualTo("./mvnw verify");
    }

    @Test
    void findsNothing() {
        assertThat(findWith("README.md")).isEmpty();
    }

    @Test
    void recognizesAJavaRepositoryByThePom() {
        assertThat(BuildFinder.isJavaRepository(Set.of("pom.xml")::contains)).isTrue();
        assertThat(BuildFinder.isJavaRepository(Set.of("package.json")::contains)).isFalse();
    }

    @Test
    void makesTheToolsOfACustomBuildFromTheFirstWord() {
        RepositoryBuild build = RepositoryBuild.custom("make test", false);
        assertThat(build.allowedTools()).isEqualTo(List.of("Bash(make *)"));
    }
}
