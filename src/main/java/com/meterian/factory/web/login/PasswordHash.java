package com.meterian.factory.web.login;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** A salted PBKDF2 hash of the password. The text form is pbkdf2-sha256:iterations:salt:key, in Base64. */
public final class PasswordHash {

    public static final int DEFAULT_ITERATIONS = 600_000;

    private static final String PREFIX = "pbkdf2-sha256";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int TEXT_PARTS = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;
    private final byte[] salt;
    private final byte[] key;

    private PasswordHash(int iterations, byte[] salt, byte[] key) {
        this.iterations = iterations;
        this.salt = salt;
        this.key = key;
    }

    /** Makes a hash with a new random salt, and clears the password array. */
    public static PasswordHash create(char[] password) {
        return create(password, DEFAULT_ITERATIONS);
    }

    public static PasswordHash create(char[] password, int iterations) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return new PasswordHash(iterations, salt, deriveKey(password, salt, iterations));
    }

    public static PasswordHash parse(String text) {
        String[] parts = text.split(":", -1);
        if (parts.length != TEXT_PARTS || !parts[0].equals(PREFIX)) {
            throw new IllegalArgumentException("The password hash has another format.");
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] key = Base64.getDecoder().decode(parts[3]);
            if (iterations < 1 || salt.length == 0 || key.length == 0) {
                throw new IllegalArgumentException("The password hash has another format.");
            }
            return new PasswordHash(iterations, salt, key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("The password hash has another format.", exception);
        }
    }

    /** Compares in constant time, and clears the password array. */
    public boolean matches(char[] password) {
        byte[] candidate = deriveKey(password, salt, iterations, key.length * Byte.SIZE);
        return MessageDigest.isEqual(key, candidate);
    }

    public int iterations() {
        return iterations;
    }

    public String format() {
        Base64.Encoder encoder = Base64.getEncoder();
        return String.join(":", PREFIX, Integer.toString(iterations),
                encoder.encodeToString(salt), encoder.encodeToString(key));
    }

    private static byte[] deriveKey(char[] password, byte[] salt, int iterations) {
        return deriveKey(password, salt, iterations, KEY_BITS);
    }

    private static byte[] deriveKey(char[] password, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec specification = new PBEKeySpec(password, salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("The JDK cannot compute " + ALGORITHM + ".", exception);
        } finally {
            specification.clearPassword();
            Arrays.fill(password, '\0');
        }
    }
}
