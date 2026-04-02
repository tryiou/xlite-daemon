import io.cloudchains.app.crypto.KeyHandler;
import io.cloudchains.app.util.ConfigHelper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KeyHandler.
 *
 * <p>Key design choices:
 * <ul>
 *   <li>{@code @TempDir} is declared as a static field so the same directory is
 *       reused across all tests in this class and cleaned up automatically by JUnit 5.
 *   <li>Passphrases are {@code char[]} throughout, matching the production API.
 *       Each test allocates a fresh array and zero-fills it in a {@code finally} block.
 *   <li>The legacy wallet helper uses {@code java.util.Base64} (standard library)
 *       instead of a third-party encoder, removing an accidental cross-library coupling.
 * </ul>
 *
 * <p><b>Thread safety</b>: {@code ConfigHelper.CONFIG_DIR} is a mutable static field.
 * Do not run these tests with JUnit 5 parallel execution enabled unless you isolate
 * the static state per worker.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KeyHandlerTest {

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private static final String TEST_PASSPHRASE = "testPassphrase123!";
    private static final String TEST_MNEMONIC =
            "one two three cake neutral benefit quick hip level mother fine burst";
    private static final List<String> TEST_MNEMONIC_LIST =
            Arrays.asList(TEST_MNEMONIC.split(" "));

    // Expected scores for calculatePasswordStrength — named so assertion failures
    // are self-documenting.
    private static final int SCORE_TOO_SHORT = 0;   // < 8 chars
    private static final int SCORE_EIGHT_LOWERCASE_ONLY = 3;   // 1 (len 8-9) + 2 (lower)
    private static final int SCORE_TEN_LOWER_DIGIT = 6;   // 2 (len 10+) + 2 (lower) + 2 (digit)
    private static final int SCORE_ALL_CRITERIA = 10;  // 2+2+2+2+2

    /** Shared temp directory — JUnit 5 removes it and all contents after the test class. */
    @TempDir
    static Path tempDir;

    private File testKeyFile;
    private File testBackupDir;

    @BeforeEach
    void setUp() {
        // Point ConfigHelper at the isolated temp directory for every test.
        ConfigHelper.CONFIG_DIR = tempDir.toString();
        testKeyFile = tempDir.resolve("CloudChains").resolve("key.dat").toFile();
        testBackupDir = tempDir.resolve("CloudChains").resolve("backups").toFile();
    }

    // =========================================================================
    // calculatePasswordStrength
    // =========================================================================

    @Test
    @Order(1)
    void testPasswordStrengthTooShort() {
        assertEquals(SCORE_TOO_SHORT, KeyHandler.calculatePasswordStrength("short"));
    }

    @Test
    @Order(2)
    void testPasswordStrengthEightCharLowercaseOnly() {
        // 1 (8-9 chars) + 2 (lowercase) = 3
        assertEquals(SCORE_EIGHT_LOWERCASE_ONLY,
                KeyHandler.calculatePasswordStrength("eightchr"));
    }

    @Test
    @Order(3)
    void testPasswordStrengthNineCharSameAsEight() {
        // Both 8 and 9 characters should yield the same length bonus (+1).
        assertEquals(
                KeyHandler.calculatePasswordStrength("eightchr"),
                KeyHandler.calculatePasswordStrength("ninechars"),
                "8-char and 9-char passwords must receive the same length bonus"
        );
    }

    @Test
    @Order(4)
    void testPasswordStrengthTenPlusWithDigitAndLower() {
        // 2 (10+ chars) + 2 (digit) + 2 (lowercase) = 6
        assertEquals(SCORE_TEN_LOWER_DIGIT,
                KeyHandler.calculatePasswordStrength("tenchars12"));
    }

    @Test
    @Order(5)
    void testPasswordStrengthAllCriteria() {
        assertEquals(SCORE_ALL_CRITERIA,
                KeyHandler.calculatePasswordStrength("StrongPass123!"));
    }

    // =========================================================================
    // getMnemonicFromString
    // =========================================================================

    @Test
    @Order(6)
    void testMnemonicFromStringNormal() {
        List<String> result = KeyHandler.getMnemonicFromString(TEST_MNEMONIC);
        assertEquals(12, result.size());
        assertEquals("one", result.get(0));
        assertEquals("burst", result.get(11));
    }

    @Test
    @Order(7)
    void testMnemonicFromStringExtraWhitespace() {
        // Leading, trailing, and double spaces must all be collapsed.
        List<String> result = KeyHandler.getMnemonicFromString("  one  two  three  ");
        assertEquals(3, result.size());
        assertEquals("one", result.get(0));
    }

    // =========================================================================
    // mnemonicToEntropy
    // =========================================================================

    @Test
    @Order(8)
    void testMnemonicToEntropy() {
        byte[] entropy = KeyHandler.mnemonicToEntropy(TEST_MNEMONIC_LIST);
        assertNotNull(entropy, "Entropy must not be null for a valid mnemonic");
        assertEquals(16, entropy.length,
                "A 12-word BIP39 mnemonic yields 16 bytes of entropy");
    }

    // =========================================================================
    // importFromMnemonic + key file structure
    // =========================================================================

    @Test
    @Order(9)
    void testImportFromMnemonicCreatesFile() {
        char[] passphrase = TEST_PASSPHRASE.toCharArray();
        try {
            boolean success = KeyHandler.importFromMnemonic(TEST_MNEMONIC_LIST, passphrase);
            assertTrue(success, "Import should succeed for a valid mnemonic");
            assertTrue(testKeyFile.exists(), "Wallet file must be created on disk");
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    @Test
    @Order(10)
    void testImportedWalletFileHasCorrectStructure() throws IOException {
        char[] passphrase = TEST_PASSPHRASE.toCharArray();
        try {
            KeyHandler.importFromMnemonic(TEST_MNEMONIC_LIST, passphrase);
        } finally {
            Arrays.fill(passphrase, '\0');
        }

        String[] lines = readKeyFileLines();
        assertEquals(4, lines.length,
                "V2 wallet file must have exactly 4 lines: VERSION, salt, IV, ciphertext");
        assertEquals("VERSION:2", lines[0],
                "First line must be the version header");
        assertFalse(lines[1].isEmpty(), "Salt line must not be empty");
        assertFalse(lines[2].isEmpty(), "IV line must not be empty");
        assertFalse(lines[3].isEmpty(), "Ciphertext line must not be empty");
    }

    // =========================================================================
    // getBaseSeed — round-trip and error paths
    // =========================================================================

    @Test
    @Order(11)
    void testGetBaseSeedRoundTrip() {
        char[] importPass = TEST_PASSPHRASE.toCharArray();
        char[] readPass = TEST_PASSPHRASE.toCharArray();
        try {
            assertTrue(KeyHandler.importFromMnemonic(TEST_MNEMONIC_LIST, importPass));

            List<String> seed = KeyHandler.getBaseSeed(readPass);
            assertNotNull(seed, "Seed must be recoverable with the correct passphrase");
            assertEquals(12, seed.size());
            assertEquals("one", seed.get(0));
            assertEquals("burst", seed.get(11));
        } finally {
            Arrays.fill(importPass, '\0');
            Arrays.fill(readPass, '\0');
        }
    }

    @Test
    @Order(12)
    void testGetBaseSeedWrongPassphraseReturnsNull() {
        char[] importPass = TEST_PASSPHRASE.toCharArray();
        char[] wrongPass = "wrongPassword".toCharArray();
        try {
            KeyHandler.importFromMnemonic(TEST_MNEMONIC_LIST, importPass);
            assertTrue(testKeyFile.exists(),
                    "Wallet file must exist before testing wrong passphrase");

            List<String> seed = KeyHandler.getBaseSeed(wrongPass);
            assertNull(seed, "Wrong passphrase must return null");

            // Verify the wallet file was not damaged by the failed attempt.
            assertTrue(testKeyFile.exists(),
                    "Wallet file must still exist after a failed decryption");
            assertEquals(4, readKeyFileLines().length,
                    "Wallet file must retain its valid 4-line structure");
        } catch (IOException e) {
            fail("Unexpected IOException reading wallet file: " + e.getMessage());
        } finally {
            Arrays.fill(importPass, '\0');
            Arrays.fill(wrongPass, '\0');
        }
    }

    // =========================================================================
    // Backup on re-import
    // =========================================================================

    @Test
    @Order(13)
    void testReimportCreatesBackup() {
        char[] passphrase = TEST_PASSPHRASE.toCharArray();
        try {
            // First import — no backup should exist yet.
            KeyHandler.importFromMnemonic(TEST_MNEMONIC_LIST, passphrase);
            passphrase = TEST_PASSPHRASE.toCharArray(); // re-allocate after first use

            // Second import — the previous wallet must be backed up.
            KeyHandler.importFromMnemonic(TEST_MNEMONIC_LIST, passphrase);

            assertTrue(testBackupDir.exists(), "Backups directory must be created");
            File[] backups = testBackupDir.listFiles();
            assertNotNull(backups);
            assertTrue(backups.length > 0,
                    "At least one backup file must exist after a second import");
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    // =========================================================================
    // Legacy wallet migration
    // =========================================================================

    @Test
    @Order(14)
    void testLegacyWalletIsReadable() throws IOException {
        createLegacyWalletFile();

        char[] passphrase = TEST_PASSPHRASE.toCharArray();
        try {
            List<String> seed = KeyHandler.getBaseSeed(passphrase);
            assertNotNull(seed, "Legacy wallet must be decryptable with the correct passphrase");
            assertEquals(12, seed.size());
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    @Test
    @Order(15)
    void testLegacyWalletIsMigratedToV2() throws IOException {
        createLegacyWalletFile();

        char[] passphrase = TEST_PASSPHRASE.toCharArray();
        try {
            KeyHandler.getBaseSeed(passphrase);
        } finally {
            Arrays.fill(passphrase, '\0');
        }

        // After migration the key file must be V2 format.
        String[] lines = readKeyFileLines();
        assertEquals("VERSION:2", lines[0],
                "Migrated wallet must carry the V2 version header");
        assertEquals(4, lines.length,
                "Migrated wallet must have the 4-line V2 structure");
    }

    @Test
    @Order(16)
    void testLegacyMigrationCreatesLegacyBackup() throws IOException {
        createLegacyWalletFile();

        char[] passphrase = TEST_PASSPHRASE.toCharArray();
        try {
            List<String> seed = KeyHandler.getBaseSeed(passphrase);
            assertNotNull(seed);
            assertEquals(12, seed.size());
        } finally {
            Arrays.fill(passphrase, '\0');
        }

        // migrateToNewFormat places the legacy backup directly in getLocalDataDirectory(),
        // not in the backups/ subdirectory, so it is easy to locate manually.
        File dataDir = new File(ConfigHelper.getLocalDataDirectory());
        File[] legacyBackups = dataDir.listFiles(
                (dir, name) -> name.startsWith("key-backup-legacy-"));
        assertNotNull(legacyBackups);
        assertTrue(legacyBackups.length > 0,
                "A timestamped legacy backup must be created during migration");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Write a valid V1 (AES-ECB / PBKDF2-SHA-1 / 16 384 iterations) wallet file
     * to the path that KeyHandler will read.
     *
     * <p>Uses only {@link java.util.Base64} (standard library) — no third-party encoder.
     */
    private void createLegacyWalletFile() throws IOException {
        testKeyFile.getParentFile().mkdirs();
        try {
            SecureRandom rng = new SecureRandom();
            byte[] salt = new byte[20];
            rng.nextBytes(salt);

            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            PBEKeySpec spec = new PBEKeySpec(TEST_PASSPHRASE.toCharArray(), salt, 16_384, 256);
            SecretKey tmp = skf.generateSecret(spec);
            SecretKey key = new SecretKeySpec(tmp.getEncoded(), "AES");
            spec.clearPassword();

            Cipher cipher = Cipher.getInstance("AES");   // defaults to ECB — intentional for V1
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(TEST_MNEMONIC.getBytes(StandardCharsets.UTF_8));

            // V1 format: two lines — Base64(salt) then Base64(ciphertext).
            Base64.Encoder encoder = Base64.getEncoder();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(testKeyFile))) {
                writer.write(encoder.encodeToString(salt));
                writer.newLine();
                writer.write(encoder.encodeToString(encrypted));
                writer.newLine();
            }
        } catch (Exception e) {
            throw new IOException("Failed to create legacy wallet file for test", e);
        }
    }

    /** Read the wallet file and return its lines, or an empty array if absent. */
    private String[] readKeyFileLines() throws IOException {
        if (!testKeyFile.exists()) return new String[0];
        try (BufferedReader reader = new BufferedReader(new FileReader(testKeyFile))) {
            return reader.lines().toArray(String[]::new);
        }
    }
}