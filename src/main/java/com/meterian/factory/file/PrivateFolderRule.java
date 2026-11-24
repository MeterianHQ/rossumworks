package com.meterian.factory.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;

/** The rule that decides if another user can rename or replace the files in a folder. */
public final class PrivateFolderRule {

    private static final int STICKY_BIT = 01000;
    private static final int GROUP_WRITE_BIT = 0020;
    private static final int OTHERS_WRITE_BIT = 0002;
    private static final int ROOT_USER_ID = 0;

    private PrivateFolderRule() {
    }

    /** Reads the mode, the owner and the group of the path, without a link. The check reads a link, not its target. */
    public static boolean isPrivate(Path folder) throws IOException {
        int mode = readMode(folder);
        int ownerId = (Integer) Files.getAttribute(folder, "unix:uid", LinkOption.NOFOLLOW_LINKS);
        int groupId = (Integer) Files.getAttribute(folder, "unix:gid", LinkOption.NOFOLLOW_LINKS);
        String groupName = readGroupName(folder);
        return isSafe(mode, ownerId, groupId, groupName, ProcessIdentity.current());
    }

    public static int readMode(Path folder) throws IOException {
        return (Integer) Files.getAttribute(folder, "unix:mode", LinkOption.NOFOLLOW_LINKS);
    }

    public static String readGroupName(Path folder) throws IOException {
        return Files.readAttributes(folder, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .group().getName();
    }

    public static boolean isWritableByOthers(int mode) {
        return (mode & OTHERS_WRITE_BIT) != 0;
    }

    /**
     * A folder passes when the group and others cannot write to it. It also passes when it is sticky and belongs
     * to root or to the user, or when only the private group of the user can write to it.
     */
    public static boolean isSafe(int mode, int ownerId, int groupId, String groupName, ProcessIdentity identity) {
        boolean isWritableByGroup = (mode & GROUP_WRITE_BIT) != 0;
        if (!isWritableByOthers(mode) && !isWritableByGroup) {
            return true;
        }
        boolean isSticky = (mode & STICKY_BIT) != 0;
        if (isSticky && (ownerId == ROOT_USER_ID || ownerId == identity.userId())) {
            return true;
        }
        boolean hasPrivateGroup = ownerId == identity.userId() && groupId == identity.groupId()
                && groupName.equals(identity.userName());
        return !isWritableByOthers(mode) && hasPrivateGroup;
    }

    /** The user and the group of the process, read once from a file that the process creates. */
    public record ProcessIdentity(int userId, int groupId, String userName) {

        private static ProcessIdentity current;

        public static synchronized ProcessIdentity current() throws IOException {
            if (current == null) {
                Path probe = Files.createTempFile("factory-owner-probe", ".tmp");
                try {
                    current = new ProcessIdentity((Integer) Files.getAttribute(probe, "unix:uid"),
                            (Integer) Files.getAttribute(probe, "unix:gid"), System.getProperty("user.name"));
                } finally {
                    Files.deleteIfExists(probe);
                }
            }
            return current;
        }
    }
}
