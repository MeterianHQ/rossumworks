package com.meterian.factory.configuration;

import java.util.Arrays;
import java.util.Optional;

/** The provider of the claude sessions. The value of claude.provider in worker.yaml selects one. */
public enum ProviderName {
    SUBSCRIPTION("subscription"),
    ANTHROPIC_API_KEY("anthropic-api-key");

    private final String configurationValue;

    ProviderName(String configurationValue) {
        this.configurationValue = configurationValue;
    }

    public String configurationValue() {
        return configurationValue;
    }

    public static Optional<ProviderName> findByConfigurationValue(String value) {
        return Arrays.stream(values()).filter(name -> name.configurationValue.equals(value)).findFirst();
    }
}
