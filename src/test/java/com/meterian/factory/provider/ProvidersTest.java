package com.meterian.factory.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.configuration.ConfigurationLoader;
import com.meterian.factory.configuration.FactoryConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProvidersTest {

    private static final String KEY = "sk-ant-test-key";

    @TempDir
    Path temporaryDirectory;

    @Test
    void stripsTheWhitespaceAroundTheKey() throws Exception {
        Path workDirectory = Files.createDirectory(temporaryDirectory.resolve("work"));
        Files.setPosixFilePermissions(workDirectory,
                PosixFilePermissions.fromString("rwx------"));
        FactoryConfiguration configuration = load(workDirectory, "anthropic-api-key");

        Providers.create(configuration, Map.of("ANTHROPIC_API_KEY", "  " + KEY + " \n"));

        Process process = new ProcessBuilder(workDirectory.resolve("credentials/api-key-helper.sh").toString())
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        assertThat(output).isEqualTo(KEY + "\n");
    }

    @Test
    void aSubscriptionStartDeletesTheKeyFilesOfAnEarlierStart() throws Exception {
        Path workDirectory = Files.createDirectory(temporaryDirectory.resolve("work"));
        Files.setPosixFilePermissions(workDirectory, PosixFilePermissions.fromString("rwx------"));
        Providers.create(load(workDirectory, "anthropic-api-key"), Map.of("ANTHROPIC_API_KEY", KEY));

        Provider provider = Providers.create(load(workDirectory, "subscription"), Map.of());

        assertThat(provider).isInstanceOf(SubscriptionProvider.class);
        assertThat(workDirectory.resolve(ApiKeyFiles.FOLDER_NAME)).doesNotExist();
    }

    private FactoryConfiguration load(Path workDirectory, String provider) throws Exception {
        Path file = temporaryDirectory.resolve("factory.yaml");
        Files.writeString(file, """
                repository: octocat/hello-world
                work-directory: %s
                gatekeeper-directory: /work/gatekeeper.git
                claude:
                  provider: %s
                limits:
                  daily-budget-usd: 20
                """.formatted(workDirectory, provider));
        return ConfigurationLoader.load(file);
    }
}
