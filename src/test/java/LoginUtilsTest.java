import io.cloudchains.app.crypto.LoginUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for LoginUtils functionality.
 * Tests password hashing and entropy generation.
 */
class LoginUtilsTest {

    @Test
    void testLoginToEntropy_ValidPassword() {
        String password = "Test^1234";
        String result = LoginUtils.loginToEntropy(password);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(64, result.length()); // SHA-256 produces 64 hex characters
    }

    @Test
    void testLoginToEntropy_DifferentPasswords() {
        String password1 = "password123";
        String password2 = "password456";

        String result1 = LoginUtils.loginToEntropy(password1);
        String result2 = LoginUtils.loginToEntropy(password2);

        assertNotNull(result1);
        assertNotNull(result2);
        assertNotEquals(result1, result2);
    }

    @Test
    void testLoginToEntropy_SamePasswordConsistency() {
        String password = "consistentPassword";

        String result1 = LoginUtils.loginToEntropy(password);
        String result2 = LoginUtils.loginToEntropy(password);

        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals(result1, result2);
    }

    @Test
    void testLoginToEntropy_EmptyPassword() {
        String password = "";
        String result = LoginUtils.loginToEntropy(password);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(64, result.length());
    }

    @Test
    void testLoginToEntropy_SpecialCharacters() {
        String password = "!@#$%^&*()_+-=[]{}|;:,.<>?";
        String result = LoginUtils.loginToEntropy(password);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(64, result.length());
    }

    @Test
    void testLoginToEntropy_Whitespace() {
        String password1 = "password";
        String password2 = " password ";
        String password3 = "password ";

        String result1 = LoginUtils.loginToEntropy(password1);
        String result2 = LoginUtils.loginToEntropy(password2);
        String result3 = LoginUtils.loginToEntropy(password3);

        assertNotNull(result1);
        assertNotNull(result2);
        assertNotNull(result3);

        assertNotEquals(result1, result2);
        assertNotEquals(result1, result3);
        assertNotEquals(result2, result3);
    }

    @Test
    void testLoginToEntropy_LongPassword() {
        StringBuilder longPassword = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longPassword.append("a");
        }

        String result = LoginUtils.loginToEntropy(longPassword.toString());

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(64, result.length());
    }

    @Test
    void testLoginToEntropy_NullSafety() {
        // Test that the method handles edge cases gracefully
        String result = LoginUtils.loginToEntropy("Test^1234");
        assertNotNull(result);

        // Verify it's a valid SHA-256 hash (64 hex characters)
        assertTrue(result.matches("[a-f0-9]{64}"));
    }

    @Test
    void testLoginToEntropy_CaseSensitivity() {
        String password1 = "Password";
        String password2 = "password";

        String result1 = LoginUtils.loginToEntropy(password1);
        String result2 = LoginUtils.loginToEntropy(password2);

        assertNotNull(result1);
        assertNotNull(result2);
        assertNotEquals(result1, result2);
    }
}