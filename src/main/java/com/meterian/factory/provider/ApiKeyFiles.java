package com.meterian.factory.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meterian.factory.agent.ShellQuoting;
import com.meterian.factory.file.AtomicFileWriter;
import com.meterian.factory.file.PrivateFolderRule;
import com.meterian.factory.file.PrivateFolderRule.ProcessIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
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

    /**
     * Writes the files in the work folder. The work folder must be private, because a link or a rename in it
     * could redirect the key. Returns the absolute path of the settings file that names the helper.
     */
    public static Path write(Path workDirectory, String apiKey) throws IOException {
        Path absoluteWorkDirectory = workDirectory.toAbsolutePath();
        createWorkFolderIfMissing(absoluteWorkDirectory);
        verifyWorkFolderIsPrivate(absoluteWorkDirectory);
        Path folder = preparePrivateFolder(absoluteWorkDirectory.resolve(FOLDER_NAME));
        Path helper = folder.resolve(HELPER_FILE_NAME);
        AtomicFileWriter.write(helper, createHelper(apiKey), PRIVATE_EXECUTABLE_PERMISSIONS);
        Path settings = folder.resolve(SETTINGS_FILE_NAME);
        AtomicFileWriter.write(settings, createSettings(helper), PRIVATE_FILE_PERMISSIONS);
        return settings;
    }

    private static void createWorkFolderIfMissing(Path workDirectory) throws IOException {
        Files.createDirectories(workDirectory, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString(PRIVATE_DIRECTORY_PERMISSIONS)));
    }

    private static void verifyWorkFolderIsPrivate(Path workDirectory) throws IOException {
        if (!PrivateFolderRule.isPrivate(workDirectory)) {
            throw new IOException("The work folder " + workDirectory + " is writable by other users."
                    + " Remove the write permission of the other users, and of the group if it is not your"
                    + " private group.");
        }
    }

    private static Path preparePrivateFolder(Path folder) throws IOException {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(folder, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(PRIVATE_DIRECTORY_PERMISSIONS)));
        }
        verifyFolderIsOwnedByTheUser(folder);
        Files.getFileAttributeView(folder, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .setPermissions(PosixFilePermissions.fromString(PRIVATE_DIRECTORY_PERMISSIONS));
        return folder;
    }

    private static void verifyFolderIsOwnedByTheUser(Path folder) throws IOException {
        PosixFileAttributes attributes = Files.readAttributes(folder, PosixFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
            throw new IOException("The folder " + folder + " is a symbolic link. Use a real folder.");
        }
        if (!attributes.isDirectory()) {
            throw new IOException("The path " + folder + " is not a folder. Remove it.");
        }
        int ownerId = (Integer) Files.getAttribute(folder, "unix:uid", LinkOption.NOFOLLOW_LINKS);
        if (ownerId != ProcessIdentity.current().userId()) {
            throw new IOException("The folder " + folder + " does not belong to the current user.");
        }
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
