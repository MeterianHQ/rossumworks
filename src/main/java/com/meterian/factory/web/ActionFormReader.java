package com.meterian.factory.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Reads the field "state" of an action form in application/x-www-form-urlencoded. The reader reads at most
 * 4 KiB. The value is a hexadecimal fingerprint, so the reader does not decode it: a value with an escape is
 * different from every fingerprint.
 */
final class ActionFormReader {

    static final int MAXIMUM_BODY_BYTES = 4096;
    private static final String FIELD_PREFIX = CardFingerprint.FIELD_NAME + "=";

    /** A body that is larger than the maximum. */
    static final class BodyTooLargeException extends Exception {

        BodyTooLargeException() {
            super("The body is larger than " + MAXIMUM_BODY_BYTES + " bytes.");
        }
    }

    Optional<String> readState(InputStream body) throws IOException, BodyTooLargeException {
        String form = readBounded(body);
        return Arrays.stream(form.split("&"))
                .filter(field -> field.startsWith(FIELD_PREFIX))
                .map(field -> field.substring(FIELD_PREFIX.length()))
                .findFirst();
    }

    private static String readBounded(InputStream body) throws IOException, BodyTooLargeException {
        byte[] bytes = body.readNBytes(MAXIMUM_BODY_BYTES + 1);
        if (bytes.length > MAXIMUM_BODY_BYTES) {
            throw new BodyTooLargeException();
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
