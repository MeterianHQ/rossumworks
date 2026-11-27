package com.meterian.factory.process;

import java.util.Locale;

/** The systems where the worker runs. */
public enum OperatingSystem {

    LINUX,
    MACOS;

    private static final String NAME_PROPERTY = "os.name";
    private static final String MACOS_NAME_START = "mac";

    public static OperatingSystem detect() {
        return parseName(System.getProperty(NAME_PROPERTY));
    }

    /** Java gives the name "Mac OS X" on macOS. Each other name gets the way of Linux. */
    static OperatingSystem parseName(String name) {
        return name.toLowerCase(Locale.ROOT).startsWith(MACOS_NAME_START) ? MACOS : LINUX;
    }
}
