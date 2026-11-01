package com.meterian.factory.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class ConfigurationLoader {

    private ConfigurationLoader() {
    }

    public static FactoryConfiguration load(Path file) throws ConfigurationException {
        ConfigurationSection root = new ConfigurationSection(readTree(file), "");
        ConfigurationSection accountsSection = root.requireSection("accounts");
        return new FactoryConfiguration(
                readTarget(root.requireSection("target")),
                root.requireNestedText("merge", "target-branch"),
                readLimits(root),
                readAllowedAccounts(accountsSection),
                readIsolationMode(root),
                readClaudeSettings(root),
                root.requirePath("work-directory"),
                root.requirePath("gatekeeper-directory"),
                root.findPath("prompts-directory"));
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
        Limits defaults = Limits.defaults();
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
            throw section.invalid(key, "must be 1 or more");
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
        try {
            return PollIntervalParser.parse(text);
        } catch (IllegalArgumentException exception) {
            throw section.invalid(key, "is not valid. " + exception.getMessage());
        }
    }

    private static double readUsageThreshold(ConfigurationSection section, double defaultValue)
            throws ConfigurationException {
        double value = section.findNumber("usage-threshold").orElse(defaultValue);
        if (value <= 0.0 || value > 1.0) {
            throw section.invalid("usage-threshold", "must be more than 0 and at most 1");
        }
        return value;
    }

    private static List<String> readAllowedAccounts(ConfigurationSection section) throws ConfigurationException {
        List<String> accounts = section.requireTextList("allowed");
        if (accounts.isEmpty()) {
            throw section.invalid("allowed", "must have at least one account");
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
            return ClaudeSettings.defaults();
        }
        return new ClaudeSettings(
                section.findText("command").orElse(ClaudeSettings.DEFAULT_COMMAND),
                section.findTextList("allowed-tools").orElse(ClaudeSettings.DEFAULT_ALLOWED_TOOLS),
                section.findTextList("disallowed-tools").orElse(ClaudeSettings.DEFAULT_DISALLOWED_TOOLS));
    }
}
