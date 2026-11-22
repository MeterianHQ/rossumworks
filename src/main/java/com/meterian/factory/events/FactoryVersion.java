package com.meterian.factory.events;

/** The version of the factory, from the manifest of the jar. */
public final class FactoryVersion {

    private static final String UNKNOWN_VERSION = "unknown";

    private FactoryVersion() {
    }

    /** A run from the classes, for example in a test, has no manifest: the version is unknown. */
    public static String current() {
        String version = FactoryVersion.class.getPackage().getImplementationVersion();
        return version == null ? UNKNOWN_VERSION : version;
    }
}
