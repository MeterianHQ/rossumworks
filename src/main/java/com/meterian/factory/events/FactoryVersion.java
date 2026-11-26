package com.meterian.factory.events;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** The version of the factory. Maven writes it into a resource, so the jar and the native program read it. */
public final class FactoryVersion {

    private static final String RESOURCE = "/factory-version.properties";
    private static final String VERSION_KEY = "version";
    private static final String UNKNOWN_VERSION = "unknown";

    private FactoryVersion() {
    }

    /** A build without the resource gives the version unknown. */
    public static String current() {
        try (InputStream stream = FactoryVersion.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return UNKNOWN_VERSION;
            }
            Properties properties = new Properties();
            properties.load(stream);
            return properties.getProperty(VERSION_KEY, UNKNOWN_VERSION);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, exception);
        }
    }
}
