package com.meterian.factory.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meterian.factory.agent.ShellQuoting;
import com.meterian.factory.file.AtomicFileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** Writes the apiKeyHelper script and its settings file in a folder that only the owner can read. */
public final class ApiKeyFiles {

    public static final String FOLDER_NAME = "credentials";

    private static final String HELPER_FILE_NAME = "api-key-helper.sh";
    private static final String SETTINGS_FILE_NAME = "api-key-settings.json";
    private static final String PRIVATE_DIRECTORY_PERMISSIONS = "rwx------";
    private static final String PRIVATE_EXECUTABLE_PERMISSIONS = "rwx------";
    private static final String PRIVATE_FILE_PERMISSIONS = "rw-------";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ApiKeyFiles() {
    }

    /** Returns the path of the settings file that names the helper. */
    public static Path write(Path workDirectory, String apiKey) throws IOException {
        Path folder = preparePrivateFolder(workDirectory.resolve(FOLDER_NAME));
        Path helper = folder.resolve(HELPER_FILE_NAME);
        AtomicFileWriter.write(helper, createHelper(apiKey), PRIVATE_EXECUTABLE_PERMISSIONS);
        Path settings = folder.resolve(SETTINGS_FILE_NAME);
        AtomicFileWriter.write(settings, createSettings(helper), PRIVATE_FILE_PERMISSIONS);
        return settings;
    }

    private static Path preparePrivateFolder(Path folder) throws IOException {
        if (Files.isSymbolicLink(folder)) {
            throw new IOException("The folder " + folder + " is a symbolic link. Use a real folder.");
        }
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(folder.getParent());
            Files.createDirectory(folder, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(PRIVATE_DIRECTORY_PERMISSIONS)));
        }
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString(PRIVATE_DIRECTORY_PERMISSIONS));
        return folder;
    }

    private static byte[] createHelper(String apiKey) {
        String script = "#!/bin/sh\n" + ShellQuoting.join(List.of("printf", "%s\\n", apiKey)) + "\n";
        return script.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] createSettings(Path helper) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("apiKeyHelper", helper.toString());
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }
}
