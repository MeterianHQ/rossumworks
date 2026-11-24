package com.meterian.factory.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.file.PrivateFolderRule.ProcessIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateFolderRuleTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void aFolderOfTheCurrentUserWithThePrivateGroupMayBeWritableByTheGroup() {
        ProcessIdentity me = new ProcessIdentity(1000, 1000, "alice");

        assertThat(PrivateFolderRule.isSafe(0775, 1000, 1000, "alice", me)).isTrue();
    }

    @Test
    void aFolderWithAnotherGroupThatCanWriteIsNotSafe() {
        ProcessIdentity me = new ProcessIdentity(1000, 1000, "alice");

        assertThat(PrivateFolderRule.isSafe(0775, 1000, 4, "adm", me)).isFalse();
        assertThat(PrivateFolderRule.isSafe(0775, 1000, 1000, "staff", me)).isFalse();
        assertThat(PrivateFolderRule.isSafe(0775, 0, 1000, "alice", me)).isFalse();
    }

    @Test
    void aFolderThatOthersCanWriteIsNotSafeWithThePrivateGroup() {
        ProcessIdentity me = new ProcessIdentity(1000, 1000, "alice");

        assertThat(PrivateFolderRule.isSafe(0777, 1000, 1000, "alice", me)).isFalse();
        assertThat(PrivateFolderRule.isSafe(01777, 1000, 1000, "alice", me)).isTrue();
        assertThat(PrivateFolderRule.isSafe(01777, 0, 0, "root", me)).isTrue();
        assertThat(PrivateFolderRule.isSafe(01777, 1001, 1001, "bob", me)).isFalse();
    }

    @Test
    void aFolderThatOthersCanWriteIsNotPrivate() throws Exception {
        Files.setPosixFilePermissions(temporaryDirectory, PosixFilePermissions.fromString("rwx---rwx"));

        assertThat(PrivateFolderRule.isPrivate(temporaryDirectory)).isFalse();
    }

    @Test
    void aFolderThatOnlyTheOwnerCanWriteIsPrivate() throws Exception {
        Files.setPosixFilePermissions(temporaryDirectory, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThat(PrivateFolderRule.isPrivate(temporaryDirectory)).isTrue();
    }
}
