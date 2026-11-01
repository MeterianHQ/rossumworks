package com.meterian.factory.configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record FactoryConfiguration(
        TargetRepository target,
        String mergeTargetBranch,
        ProjectReference project,
        Limits limits,
        List<String> allowedAccounts,
        IsolationMode isolationMode,
        ClaudeSettings claude,
        Path workDirectory,
        Path gatekeeperDirectory,
        Optional<Path> promptDirectory) {
}
