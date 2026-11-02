package com.meterian.factory.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.meterian.factory.web.login.PasswordHash;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class ConfigurationLoader {

    /** A shorter interval uses up the rate limit of the GitHub API. */
    private static final Duration MINIMUM_POLL_INTERVAL = Duration.ofSeconds(10);

    /** A hash with fewer iterations is too quick to guess. */
    private static final int MINIMUM_HASH_ITERATIONS = 100_000;

    private ConfigurationLoader() {
    }

    public static FactoryConfiguration load(Path file) throws ConfigurationException {
        ConfigurationSection root = new ConfigurationSection(readTree(file), "");
        ConfigurationSection accountsSection = root.requireSection("accounts");
        Path workDirectory = root.requirePath("work-directory");
        return new FactoryConfiguration(
                readTarget(root.requireSection("target")),
                root.requireNestedText("merge", "target-branch"),
                readLimits(root),
                readAllowedAccounts(accountsSection),
                readIsolationMode(root),
                readClaudeSettings(root),
                workDirectory,
                root.requirePath("gatekeeper-directory"),
                root.findPath("prompts-directory"),
                root.findPath("live-directory").orElse(workDirectory.resolve("live")),
                readWebSettings(root),
                readTerminalCommand(root));
    }

    private static JsonNode readTree(Path file) throws ConfigurationException {
        try {
            JsonNode tree = new YAMLMapper().readTree(file.toFile());
            if (tree == null || !tree.isObject()) {
                throw new ConfigurationException("The configuration file " + file + " is empty.");
            }
            return tree;
        } catch (IOException exception) {
            throw new ConfigurationException("Cannot read the configuration file " + file
                    + ": " + exception.getMessage(), exception);
        }
    }

    private static TargetRepository readTarget(ConfigurationSection section) throws ConfigurationException {
        ConfigurationSection build = section.requireSection("build");
        return new TargetRepository(
                section.requireText("owner"),
                section.requireText("name"),
                section.requirePath("clone"),
                build.requireText("command"),
                build.findTextMap("environment"));
    }

    private static Limits readLimits(ConfigurationSection root) throws ConfigurationException {
        Limits defaults = Limits.createDefaults();
        ConfigurationSection section = root.findSection("limits").orElse(null);
        if (section == null) {
            return defaults;
        }
        return new Limits(
                readPositiveInteger(section, "parallel-issues", defaults.parallelIssues()),
                readPositiveInteger(section, "fix-loops", defaults.fixLoops()),
                readPollInterval(section, defaults.pollInterval()),
                readUsageThreshold(section, defaults.usageThreshold()));
    }

    private static int readPositiveInteger(ConfigurationSection section, String key, int defaultValue)
            throws ConfigurationException {
        int value = section.findInteger(key).orElse(defaultValue);
        if (value < 1) {
            throw section.createInvalidKeyException(key, "must be 1 or more");
        }
        return value;
    }

    private static Duration readPollInterval(ConfigurationSection section, Duration defaultValue)
            throws ConfigurationException {
        String key = "poll-interval";
        String text = section.findText(key).orElse(null);
        if (text == null) {
            return defaultValue;
        }
        Duration interval;
        try {
            interval = PollIntervalParser.parse(text);
        } catch (IllegalArgumentException exception) {
            throw section.createInvalidKeyException(key, "is not valid. " + exception.getMessage());
        }
        if (interval.compareTo(MINIMUM_POLL_INTERVAL) < 0) {
            throw section.createInvalidKeyException(key, "must be 10 seconds or more");
        }
        return interval;
    }

    private static double readUsageThreshold(ConfigurationSection section, double defaultValue)
            throws ConfigurationException {
        double value = section.findNumber("usage-threshold").orElse(defaultValue);
        boolean isInRange = value > 0.0 && value <= 1.0;
        if (!isInRange) {
            throw section.createInvalidKeyException("usage-threshold", "must be more than 0 and at most 1");
        }
        return value;
    }

    private static List<String> readAllowedAccounts(ConfigurationSection section) throws ConfigurationException {
        List<String> accounts = section.requireTextList("allowed");
        if (accounts.isEmpty()) {
            throw section.createInvalidKeyException("allowed", "must have at least one account");
        }
        return accounts;
    }

    private static IsolationMode readIsolationMode(ConfigurationSection root) throws ConfigurationException {
        ConfigurationSection section = root.findSection("isolation").orElse(null);
        String mode = section == null ? "same-user" : section.findText("mode").orElse("same-user");
        return switch (mode) {
            case "same-user" -> IsolationMode.SAME_USER;
            case "separate-user" -> throw new ConfigurationException(
                    "The isolation.mode separate-user is not available in this version.");
            default -> throw new ConfigurationException("Unknown isolation.mode " + mode + ".");
        };
    }

    private static ClaudeSettings readClaudeSettings(ConfigurationSection root) throws ConfigurationException {
        ConfigurationSection section = root.findSection("claude").orElse(null);
        if (section == null) {
            return ClaudeSettings.createDefaults();
        }
        return new ClaudeSettings(
                section.findText("command").orElse(ClaudeSettings.DEFAULT_COMMAND),
                section.findTextList("allowed-tools").orElse(ClaudeSettings.DEFAULT_ALLOWED_TOOLS),
                section.findTextList("disallowed-tools").orElse(ClaudeSettings.DEFAULT_DISALLOWED_TOOLS));
    }

    private static Optional<WebSettings> readWebSettings(ConfigurationSection root) throws ConfigurationException {
        ConfigurationSection section = root.findSection("web").orElse(null);
        if (section == null) {
            return Optional.empty();
        }
        return Optional.of(new WebSettings(
                section.findText("address").orElse(WebSettings.DEFAULT_ADDRESS),
                readPort(section),
                readPasswordHash(section)));
    }

    private static List<String> readTerminalCommand(ConfigurationSection root) throws ConfigurationException {
        ConfigurationSection section = root.findSection("live").orElse(null);
        if (section == null) {
            return FactoryConfiguration.DEFAULT_TERMINAL_COMMAND;
        }
        List<String> command = section.findTextList("terminal-command")
                .orElse(FactoryConfiguration.DEFAULT_TERMINAL_COMMAND);
        if (command.isEmpty()) {
            throw new ConfigurationException("live.terminal-command must have at least one value.");
        }
        return command;
    }

    private static int readPort(ConfigurationSection section) throws ConfigurationException {
        int port = section.findInteger("port").orElse(WebSettings.DEFAULT_PORT);
        if (port < 1 || port > 65535) {
            throw new ConfigurationException("web.port must be from 1 to 65535");
        }
        return port;
    }

    private static PasswordHash readPasswordHash(ConfigurationSection section) throws ConfigurationException {
        String text = section.requireText("password-hash");
        PasswordHash hash;
        try {
            hash = PasswordHash.parse(text);
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException("web.password-hash is not valid. Make a new one with hash-password.");
        }
        if (hash.iterations() < MINIMUM_HASH_ITERATIONS) {
            throw new ConfigurationException("web.password-hash is too weak. Make a new one with hash-password.");
        }
        return hash;
    }
}
