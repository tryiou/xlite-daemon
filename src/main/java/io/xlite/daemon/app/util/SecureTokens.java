package io.xlite.daemon.app.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * URL-safe random tokens for generated RPC credentials.
 *
 * <p>One shared {@link SecureRandom}: the class is thread-safe, and re-seeding a fresh instance per
 * call both wastes entropy and can block on hosts with a slow entropy source.
 */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureTokens() {}

    /**
     * Returns {@code bytes} bytes of randomness, URL-safe Base64 without padding.
     *
     * @param bytes number of random bytes; the encoded token is longer than this
     */
    public static String urlSafe(int bytes) {
        byte[] token = new byte[bytes];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
}
