package com.meterian.factory.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.configuration.RepositoryName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubRemoteUrlTest {

    private static final RepositoryName HELLO_WORLD = new RepositoryName("octocat", "hello-world");

    @ParameterizedTest
    @ValueSource(strings = {
            "git@github.com:octocat/hello-world.git",
            "ssh://git@github.com/octocat/hello-world.git",
            "https://github.com/octocat/hello-world",
            "https://github.com/octocat/hello-world.git"
    })
    void readsTheForms(String url) {
        assertThat(GitHubRemoteUrl.parse(url)).contains(HELLO_WORLD);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "git@gitlab.com:a/b.git",
            "https://github.com/only-owner",
            ""
    })
    void refusesOtherHosts(String url) {
        assertThat(GitHubRemoteUrl.parse(url)).isEmpty();
    }
}
