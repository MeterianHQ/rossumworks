package com.meterian.factory.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.configuration.RepositoryName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubRemoteUrlTest {

    private static final RepositoryName HEIDI_FIX = new RepositoryName("MeterianHQ", "heidi-fix");

    @ParameterizedTest
    @ValueSource(strings = {
            "git@github.com:MeterianHQ/heidi-fix.git",
            "ssh://git@github.com/MeterianHQ/heidi-fix.git",
            "https://github.com/MeterianHQ/heidi-fix",
            "https://github.com/MeterianHQ/heidi-fix.git"
    })
    void readsTheForms(String url) {
        assertThat(GitHubRemoteUrl.parse(url)).contains(HEIDI_FIX);
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
