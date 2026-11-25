package com.meterian.factory.setup;

import com.meterian.factory.configuration.ConfigurationException;
import com.meterian.factory.configuration.RepositoryName;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the GitHub repository name from a git remote URL. */
public final class GitHubRemoteUrl {

    private static final Pattern REMOTE_URL = Pattern.compile(
            "(?:git@github\\.com:|ssh://git@github\\.com/|https://github\\.com/)"
                    + "([^/\\s]+)/([^/\\s]+?)(?:\\.git)?");

    private GitHubRemoteUrl() {
    }

    /**
     * Reads the repository name from a GitHub remote URL.
     * The URL can use the SSH form or the HTTPS form, with or without the ".git" suffix.
     *
     * @param url the remote URL, for example git@github.com:octocat/hello-world.git
     * @return the repository name, or empty when the URL is not a GitHub repository URL
     */
    public static Optional<RepositoryName> parse(String url) {
        Matcher matcher = REMOTE_URL.matcher(url.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return toRepositoryName(matcher.group(1), matcher.group(2));
    }

    private static Optional<RepositoryName> toRepositoryName(String owner, String name) {
        try {
            return Optional.of(RepositoryName.parse(owner + "/" + name));
        } catch (ConfigurationException exception) {
            return Optional.empty();
        }
    }
}
