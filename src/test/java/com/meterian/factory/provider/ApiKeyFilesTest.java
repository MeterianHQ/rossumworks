package com.meterian.factory.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiKeyFilesTest {

    private static final String KEY = "sk-ant-test-key";

    @TempDir
    Path workDirectory;

    @Test
    void writesTheFilesForTheOwnerOnly() throws Exception {
        Path settings = ApiKeyFiles.write(workDirectory, KEY);

        Path folder = workDirectory.resolve("credentials");
        assertThat(settings).isEqualTo(folder.resolve("api-key-settings.json"));
        assertThat(permissionsOf(folder)).isEqualTo("rwx------");
        assertThat(permissionsOf(folder.resolve("api-key-helper.sh"))).isEqualTo("rwx------");
        assertThat(permissionsOf(settings)).isEqualTo("rw-------");
    }

    @Test
    void theHelperPrintsTheKey() throws Exception {
        ApiKeyFiles.write(workDirectory, KEY);

        Process process = new ProcessBuilder(workDirectory.resolve("credentials/api-key-helper.sh").toString())
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        assertThat(output).isEqualTo(KEY + "\n");
    }

    @Test
    void theSettingsFileNamesTheHelperAndNotTheKey() throws Exception {
        Path settings = ApiKeyFiles.write(workDirectory, KEY);

        assertThat(Files.readString(settings))
                .contains("\"apiKeyHelper\" : \"" + workDirectory.resolve("credentials/api-key-helper.sh") + "\"")
                .doesNotContain(KEY);
    }

    @Test
    void makesAFolderThatExistsPrivate() throws Exception {
        Path folder = Files.createDirectory(workDirectory.resolve("credentials"));
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));

        ApiKeyFiles.write(workDirectory, KEY);

        assertThat(permissionsOf(folder)).isEqualTo("rwx------");
    }

    @Test
    void refusesASymbolicLink() throws Exception {
        Path target = Files.createDirectory(workDirectory.resolve("elsewhere"));
        Files.createSymbolicLink(workDirectory.resolve("credentials"), target);

        assertThatThrownBy(() -> ApiKeyFiles.write(workDirectory, KEY))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("is a symbolic link");
    }

    @Test
    void writesAbsolutePathsForARelativeWorkFolder() throws Exception {
        Path relativeWorkDirectory = Path.of("").toAbsolutePath().relativize(workDirectory);

        Path settings = ApiKeyFiles.write(relativeWorkDirectory, KEY);

        assertThat(settings).isAbsolute();
        Path helper = Path.of(new ObjectMapper().readTree(settings.toFile()).get("apiKeyHelper").asText());
        assertThat(helper).isAbsolute();
        assertThat(helper.normalize()).isEqualTo(workDirectory.resolve("credentials/api-key-helper.sh"));
    }

    @Test
    void refusesAWorkFolderThatTheGroupCanWrite() throws Exception {
        Files.setPosixFilePermissions(workDirectory, PosixFilePermissions.fromString("rwxrwx---"));

        assertThatThrownBy(() -> ApiKeyFiles.write(workDirectory, KEY))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("is writable by other users")
                .hasMessageNotContaining(KEY);
    }

    @Test
    void refusesAWorkFolderThatOtherUsersCanWrite() throws Exception {
        Files.setPosixFilePermissions(workDirectory, PosixFilePermissions.fromString("rwx---rwx"));

        assertThatThrownBy(() -> ApiKeyFiles.write(workDirectory, KEY))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("is writable by other users");
    }

    private static String permissionsOf(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }
}
