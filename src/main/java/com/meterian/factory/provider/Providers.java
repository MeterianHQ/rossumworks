package com.meterian.factory.provider;

import com.meterian.factory.Main.StartupException;
import com.meterian.factory.configuration.FactoryConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Creates the provider of the configuration. The start checks run first, so the key and the budget exist. */
public final class Providers {

    private static final String API_KEY_VARIABLE = "ANTHROPIC_API_KEY";

    private Providers() {
    }

    public static Provider create(FactoryConfiguration configuration, Map<String, String> environment)
            throws StartupException {
        return switch (configuration.claude().provider()) {
            case SUBSCRIPTION -> createSubscriptionProvider(configuration);
            case ANTHROPIC_API_KEY -> createApiKeyProvider(configuration, environment.get(API_KEY_VARIABLE));
        };
    }

    private static Provider createSubscriptionProvider(FactoryConfiguration configuration) throws StartupException {
        try {
            ApiKeyFiles.delete(configuration.workDirectory());
            return new SubscriptionProvider();
        } catch (IOException exception) {
            throw new StartupException("The factory cannot delete the old API key files in "
                    + configuration.workDirectory().resolve(ApiKeyFiles.FOLDER_NAME) + ": " + exception.getMessage()
                    + ". Delete the folder, and start the worker again.");
        }
    }

    private static Provider createApiKeyProvider(FactoryConfiguration configuration, String apiKey)
            throws StartupException {
        try {
            Path settingsFile = ApiKeyFiles.write(configuration.workDirectory(), apiKey.strip());
            return new ApiKeyProvider(settingsFile, configuration.limits().dailyBudgetUsd().orElseThrow());
        } catch (IOException exception) {
            throw new StartupException("The factory cannot write the API key files in "
                    + configuration.workDirectory().resolve(ApiKeyFiles.FOLDER_NAME) + ": " + exception.getMessage());
        }
    }
}
