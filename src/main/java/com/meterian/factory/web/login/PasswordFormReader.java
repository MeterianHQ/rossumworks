package com.meterian.factory.web.login;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads the field "password" of a body in application/x-www-form-urlencoded.
 * The password never becomes a String: it goes into a char array, and the reader clears its own buffers.
 */
final class PasswordFormReader {

    private static final byte[] PASSWORD_FIELD = "password=".getBytes(StandardCharsets.US_ASCII);
    private static final int HEXADECIMAL = 16;

    /** A request that the reader refuses, with the HTTP status of the answer. */
    static final class RefusedFormException extends Exception {

        private final int statusCode;

        RefusedFormException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }

    private final int maximumBodyBytes;

    PasswordFormReader(int maximumBodyBytes) {
        this.maximumBodyBytes = maximumBodyBytes;
    }

    /** Reads at most the maximum number of bytes, and gives the password. The caller clears the array. */
    char[] readPassword(InputStream body) throws IOException, RefusedFormException {
        byte[] buffer = new byte[maximumBodyBytes + 1];
        try {
            int length = readBounded(body, buffer);
            if (length > maximumBodyBytes) {
                throw new RefusedFormException(413, "The body is larger than " + maximumBodyBytes + " bytes.");
            }
            return findPassword(buffer, length);
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private static int readBounded(InputStream body, byte[] buffer) throws IOException {
        int length = 0;
        while (length < buffer.length) {
            int count = body.read(buffer, length, buffer.length - length);
            if (count < 0) {
                break;
            }
            length += count;
        }
        return length;
    }

    private static char[] findPassword(byte[] buffer, int length) throws RefusedFormException {
        int fieldStart = 0;
        while (fieldStart < length) {
            int fieldEnd = findFieldEnd(buffer, fieldStart, length);
            if (startsWithPasswordField(buffer, fieldStart, fieldEnd)) {
                return decodeValue(buffer, fieldStart + PASSWORD_FIELD.length, fieldEnd);
            }
            fieldStart = fieldEnd + 1;
        }
        throw new RefusedFormException(400, "The form has no field password.");
    }

    private static int findFieldEnd(byte[] buffer, int fieldStart, int length) {
        int index = fieldStart;
        while (index < length && buffer[index] != '&') {
            index++;
        }
        return index;
    }

    private static boolean startsWithPasswordField(byte[] buffer, int fieldStart, int fieldEnd) {
        return fieldEnd - fieldStart >= PASSWORD_FIELD.length
                && Arrays.equals(buffer, fieldStart, fieldStart + PASSWORD_FIELD.length,
                        PASSWORD_FIELD, 0, PASSWORD_FIELD.length);
    }

    private static char[] decodeValue(byte[] buffer, int valueStart, int valueEnd) throws RefusedFormException {
        byte[] decoded = new byte[valueEnd - valueStart];
        try {
            int decodedLength = decodePercentEncoding(buffer, valueStart, valueEnd, decoded);
            return decodeUtf8(decoded, decodedLength);
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    private static int decodePercentEncoding(byte[] buffer, int valueStart, int valueEnd, byte[] decoded)
            throws RefusedFormException {
        int decodedLength = 0;
        int index = valueStart;
        while (index < valueEnd) {
            byte current = buffer[index];
            if (current == '+') {
                decoded[decodedLength++] = ' ';
                index++;
            } else if (current == '%') {
                decoded[decodedLength++] = decodeEscapedByte(buffer, index, valueEnd);
                index += 3;
            } else {
                decoded[decodedLength++] = current;
                index++;
            }
        }
        return decodedLength;
    }

    private static byte decodeEscapedByte(byte[] buffer, int percentIndex, int valueEnd) throws RefusedFormException {
        if (percentIndex + 2 >= valueEnd) {
            throw new RefusedFormException(400, "The form has a broken percent encoding.");
        }
        int high = Character.digit(buffer[percentIndex + 1], HEXADECIMAL);
        int low = Character.digit(buffer[percentIndex + 2], HEXADECIMAL);
        if (high < 0 || low < 0) {
            throw new RefusedFormException(400, "The form has a broken percent encoding.");
        }
        return (byte) (high * HEXADECIMAL + low);
    }

    private static char[] decodeUtf8(byte[] decoded, int decodedLength) throws RefusedFormException {
        CharBuffer characters = null;
        try {
            characters = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded, 0, decodedLength));
            char[] password = new char[characters.remaining()];
            characters.get(password);
            return password;
        } catch (CharacterCodingException exception) {
            throw new RefusedFormException(400, "The password is not valid UTF-8.");
        } finally {
            if (characters != null && characters.hasArray()) {
                Arrays.fill(characters.array(), '\0');
            }
        }
    }
}
