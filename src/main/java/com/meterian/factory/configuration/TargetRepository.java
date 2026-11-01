package com.meterian.factory.configuration;

import java.nio.file.Path;
import java.util.Map;

public record TargetRepository(String owner, String name, Path cloneDirectory, String buildCommand,
                               Map<String, String> buildEnvironment) {

    public String fullName() {
        return owner + "/" + name;
    }

    public String sshUrl() {
        return "git@github.com:" + fullName() + ".git";
    }
}
