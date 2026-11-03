package com.meterian.factory.build;

import com.meterian.factory.definition.BuildDefinition;
import java.util.LinkedHashMap;
import java.util.Map;

/** The build command and the build variables that a build of an issue uses. */
public record BuildSettings(String command, Map<String, String> environment) {

    public BuildSettings {
        environment = Map.copyOf(environment);
    }

    /**
     * The build of the definition, with the variables of the machine. A value of the machine replaces a value with
     * the same name.
     */
    public static BuildSettings combine(BuildDefinition build, Map<String, String> machineEnvironment) {
        Map<String, String> environment = new LinkedHashMap<>(build.environment());
        environment.putAll(machineEnvironment);
        return new BuildSettings(build.command(), environment);
    }
}
