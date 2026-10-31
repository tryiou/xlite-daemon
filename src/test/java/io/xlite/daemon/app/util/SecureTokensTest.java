package io.xlite.daemon.app.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecureTokensTest {

    @Test
    void producesUrlSafeBase64WithoutPadding() {
        String token = SecureTokens.urlSafe(24);

        assertTrue(token.matches("[A-Za-z0-9_-]+"), token);
        assertEquals(32, token.length(), "24 bytes encode to 32 Base64 characters");
    }

    @Test
    void successiveTokensDiffer() {
        assertNotEquals(SecureTokens.urlSafe(32), SecureTokens.urlSafe(32));
    }

    @Test
    void honoursTheRequestedByteCount() {
        assertEquals(2, SecureTokens.urlSafe(1).length(), "1 byte encodes to 2 characters");
    }
}
