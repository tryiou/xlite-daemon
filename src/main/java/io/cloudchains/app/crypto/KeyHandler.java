package io.cloudchains.app.crypto;

import com.google.common.base.Joiner;
import com.subgraph.orchid.encoders.Base64;
import io.cloudchains.app.util.ConfigHelper;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.wallet.DeterministicSeed;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Handles BIP39 mnemonic seed storage, AES-CBC encryption/decryption,
 * and automatic migration from the legacy AES-ECB (SHA-1) format.
 *
 * <p><b>Passphrase handling</b>: all public methods accept {@code char[]} so callers
 * can zero the array immediately after use. Passing a {@code String} literal is
 * intentionally unsupported — {@code String} is immutable and cannot be wiped.
 */
public class KeyHandler {

    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    // -------------------------------------------------------------------------
    // Version constants
    // -------------------------------------------------------------------------
    private static final int VERSION_1_SHA1 = 1;   // Legacy: AES-ECB, PBKDF2-SHA-1, 16k iters
    private static final int VERSION_2_SHA256 = 2;   // Current: AES-CBC, PBKDF2-SHA-256, 100k iters
    private static final int CURRENT_VERSION = VERSION_2_SHA256;
    private static final String VERSION_HEADER = "VERSION:";

    // -------------------------------------------------------------------------
    // Crypto parameters
    // -------------------------------------------------------------------------
    private static final int PBKDF2_ITERATIONS_SHA256 = 100_000;
    private static final int PBKDF2_ITERATIONS_SHA1 = 16_384;  // legacy only
    private static final int KEY_LENGTH = 256;   // AES key bits
    private static final int SALT_LENGTH = 20;   // bytes
    private static final int IV_LENGTH = 16;   // bytes, AES block size

    private static final String PBKDF2_SHA256 = "PBKDF2WithHmacSHA256";
    private static final String PBKDF2_SHA1 = "PBKDF2WithHmacSHA1";   // legacy only
    private static final String AES = "AES";
    private static final String AES_CBC = "AES/CBC/PKCS5Padding";
    private static final String AES_ECB = "AES/ECB/PKCS5Padding"; // legacy migration only

    // DateTimeFormatter is immutable and thread-safe — no SimpleDateFormat.
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    // Cache MnemonicCode: its constructor reads the BIP39 word list from disk.
    private static final MnemonicCode MNEMONIC_CODE;

    static {
        try {
            MNEMONIC_CODE = new MnemonicCode();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(
                    "Failed to load BIP39 word list: " + e.getMessage());
        }
    }

    // Characters accepted as "special" by calculatePasswordStrength.
    private static final String SPECIAL_CHARS = "~!@#$%^&*()_-+=[]{|};:',.<>?/\\";

    // -------------------------------------------------------------------------
    // Instance
    // -------------------------------------------------------------------------

    private final ECKey ecKey;

    /**
     * Wrap an existing {@link ECKey}.
     *
     * @param key the key to handle
     */
    public KeyHandler(ECKey key) {
        this.ecKey = key;
    }

    /** Return the underlying {@link ECKey}. */
    public ECKey getBaseECKey() {
        return ecKey;
    }

    /** Return a public-only view of the underlying key. */
    public ECKey getPublicKey() {
        return ECKey.fromPublicOnly(ecKey.getPubKey());
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Return {@code true} if a wallet file already exists on disk.
     */
    public static boolean existsBaseECKeyFromLocal() {
        return keyFile().exists();
    }

    /**
     * Decrypt and return the mnemonic seed phrase.
     *
     * <p>If no wallet file exists, a new one is generated and persisted.
     * Legacy wallets (V1 / AES-ECB) are migrated to V2 (AES-CBC) transparently.
     *
     * @param passphrase caller-owned char array; <b>must</b> be zeroed by the
     *                   caller immediately after this method returns
     * @return the mnemonic word list, or {@code null} if decryption fails
     */
    public static List<String> getBaseSeed(char[] passphrase) {
        File file = keyFile();
        if (!file.exists()) {
            return generateAndPersistNewSeed(passphrase, file);
        }
        try {
            WalletData data = readWalletFile(file);
            if (data.version == VERSION_1_SHA1) {
                LOGGER.log(Level.INFO,
                        "[security] Legacy V1 wallet detected — migrating to V2 (SHA-256/CBC)");
                String seed = decryptSeedEcb(passphrase, data.encrypted, data.salt);
                migrateToNewFormat(passphrase, seed, file);
                return Arrays.asList(seed.split("\\s+"));
            }
            String seed = decryptSeedCbc(passphrase, data.encrypted, data.salt, data.iv);
            return Arrays.asList(seed.split("\\s+"));
        } catch (BadPaddingException e) {
            // Wrong password — expected failure, low log level.
            LOGGER.log(Level.FINER,
                    "[security] Decryption failed — wrong passphrase or corrupted wallet");
            return null;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[security] Cannot read wallet file", e);
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[security] Unexpected error reading wallet", e);
            return null;
        }
    }

    /**
     * Import a wallet from a BIP39 mnemonic word list.
     *
     * @param mnemonicList the BIP39 word list (12 / 15 / 18 / 21 / 24 words)
     * @param passphrase   caller-owned char array; <b>must</b> be zeroed by the
     *                     caller immediately after this method returns
     * @return {@code true} on success
     * @throws IllegalArgumentException if {@code mnemonicList} is null or empty
     */
    public static boolean importFromMnemonic(List<String> mnemonicList, char[] passphrase) {
        if (mnemonicList == null || mnemonicList.isEmpty()) {
            throw new IllegalArgumentException("Mnemonic list cannot be null or empty");
        }
        byte[] entropy = null;
        try {
            entropy = MNEMONIC_CODE.toEntropy(mnemonicList);
            DeterministicSeed seed = new DeterministicSeed(
                    entropy, "", System.currentTimeMillis() / 1000);
            List<String> derived = Objects.requireNonNull(seed.getMnemonicCode());
            if (!derived.equals(mnemonicList)) {
                return false;
            }
            String mnemonic = Joiner.on(" ").join(derived);
            return writeInitialData(keyFile(), mnemonic, passphrase);
        } catch (MnemonicException e) {
            LOGGER.log(Level.WARNING, "Failed to convert mnemonic to entropy", e);
            return false;
        } finally {
            if (entropy != null) Arrays.fill(entropy, (byte) 0);
        }
    }

    /**
     * Convert a BIP39 mnemonic word list to its raw entropy bytes.
     *
     * @param mnemonicList the mnemonic word list
     * @return entropy bytes, or {@code null} on failure
     */
    public static byte[] mnemonicToEntropy(List<String> mnemonicList) {
        try {
            return MNEMONIC_CODE.toEntropy(mnemonicList);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to convert mnemonic to entropy", e);
            return null;
        }
    }

    /**
     * Parse a space-separated mnemonic string into a word list.
     * Tolerates leading/trailing whitespace and runs of multiple spaces.
     *
     * @param mnemonic the raw mnemonic string
     * @return list of mnemonic words
     */
    public static List<String> getMnemonicFromString(String mnemonic) {
        return Arrays.asList(mnemonic.trim().split("\\s+"));
    }

    /**
     * Score password strength from 0 (too short) to 10 (all criteria met).
     *
     * <table>
     *   <tr><th>Criterion</th><th>Points</th></tr>
     *   <tr><td>8–9 characters</td><td>+1</td></tr>
     *   <tr><td>10+ characters</td><td>+2</td></tr>
     *   <tr><td>Contains digit</td><td>+2</td></tr>
     *   <tr><td>Contains lowercase</td><td>+2</td></tr>
     *   <tr><td>Contains uppercase</td><td>+2</td></tr>
     *   <tr><td>Contains special char</td><td>+2</td></tr>
     * </table>
     *
     * @param password the password to evaluate
     * @return score in [0, 10]
     */
    public static int calculatePasswordStrength(String password) {
        if (password.length() < 8) return 0;

        int score = password.length() >= 10 ? 2 : 1;
        boolean hasDigit = false, hasLower = false, hasUpper = false, hasSpecial = false;

        for (char c : password.toCharArray()) {
            if (Character.isDigit(c)) {
                hasDigit = true;
            } else if (Character.isLowerCase(c)) {
                hasLower = true;
            } else if (Character.isUpperCase(c)) {
                hasUpper = true;
            } else if (SPECIAL_CHARS.indexOf(c) >= 0) {
                hasSpecial = true;
            }
        }

        if (hasDigit) score += 2;
        if (hasLower) score += 2;
        if (hasUpper) score += 2;
        if (hasSpecial) score += 2;
        return score;
    }

    // =========================================================================
    // Private — key derivation and cipher
    // =========================================================================

    /**
     * Derive a 256-bit AES key from a passphrase using PBKDF2.
     * The intermediate raw key bytes are zeroed before returning.
     */
    private static SecretKey deriveKey(char[] passphrase, byte[] salt,
                                       String algorithm, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH);
        try {
            byte[] raw = SecretKeyFactory.getInstance(algorithm)
                    .generateSecret(spec)
                    .getEncoded();
            try {
                return new SecretKeySpec(raw, AES);
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive encryption key", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Encrypt {@code seedBytes} with AES-CBC and a freshly generated random IV.
     *
     * @return {@code String[2]} — {@code [0]} = Base64 IV, {@code [1]} = Base64 ciphertext
     */
    private static String[] encryptBaseSeedWithIv(char[] passphrase,
                                                  byte[] seedBytes, byte[] salt) {
        SecretKey key = deriveKey(passphrase, salt, PBKDF2_SHA256, PBKDF2_ITERATIONS_SHA256);
        try {
            byte[] iv = new byte[IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_CBC);
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(seedBytes);
            return new String[]{
                    new String(Base64.encode(iv), StandardCharsets.UTF_8),
                    new String(Base64.encode(encrypted), StandardCharsets.UTF_8)
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt seed", e);
        }
    }

    /**
     * Decrypt with the current V2 scheme: AES-CBC, PBKDF2-SHA-256 @ 100 000 iterations.
     */
    private static String decryptSeedCbc(char[] passphrase, byte[] encrypted,
                                         byte[] salt, byte[] iv) throws Exception {
        if (iv == null) throw new IllegalArgumentException("IV must not be null for CBC decryption");
        SecretKey key = deriveKey(passphrase, salt, PBKDF2_SHA256, PBKDF2_ITERATIONS_SHA256);
        Cipher cipher = Cipher.getInstance(AES_CBC);
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    /**
     * Decrypt with the legacy V1 scheme: AES-ECB, PBKDF2-SHA-1 @ 16 384 iterations.
     * <b>Do not use for anything other than migrating legacy wallets.</b>
     */
    private static String decryptSeedEcb(char[] passphrase, byte[] encrypted,
                                         byte[] salt) throws Exception {
        SecretKey key = deriveKey(passphrase, salt, PBKDF2_SHA1, PBKDF2_ITERATIONS_SHA1);
        Cipher cipher = Cipher.getInstance(AES_ECB);
        cipher.init(Cipher.DECRYPT_MODE, key);
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    // =========================================================================
    // Private — file I/O
    // =========================================================================

    /** Canonical location of the wallet file. Single source of truth. */
    private static File keyFile() {
        return new File(ConfigHelper.getLocalDataDirectory(), "key.dat");
    }

    /**
     * Read and parse a wallet file.
     *
     * @param file the wallet file to read
     * @return parsed {@link WalletData}
     * @throws IOException if the file is missing, incomplete, or corrupted
     */
    private static WalletData readWalletFile(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String firstLine = reader.readLine();
            int version = detectWalletVersion(firstLine);

            String saltB64, ivB64 = null, encB64;
            if (version == VERSION_2_SHA256) {
                saltB64 = reader.readLine();
                ivB64 = reader.readLine();
                encB64 = reader.readLine();
            } else {
                // V1: first line IS the salt; no IV.
                saltB64 = firstLine;
                encB64 = reader.readLine();
            }

            if (saltB64 == null || encB64 == null) {
                throw new IOException("Wallet file is incomplete or corrupted");
            }

            return new WalletData(
                    version,
                    Base64.decode(saltB64),
                    ivB64 != null ? Base64.decode(ivB64) : null,
                    Base64.decode(encB64)
            );
        }
    }

    /**
     * Write the versioned 4-line wallet format to {@code file}.
     * Creates parent directories if they do not exist.
     */
    private static void writeWalletFile(File file, byte[] salt,
                                        String ivB64, String encryptedSeed) throws IOException {
        file.getParentFile().mkdirs();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(VERSION_HEADER + CURRENT_VERSION);
            writer.newLine();
            writer.write(new String(Base64.encode(salt), StandardCharsets.UTF_8));
            writer.newLine();
            writer.write(ivB64);
            writer.newLine();
            writer.write(encryptedSeed);
            writer.newLine();
        }
    }

    /**
     * Move the current wallet file into the {@code backups/} subdirectory with
     * a timestamp suffix.
     *
     * @param keyFile the wallet file to back up
     * @return the backup {@link File}, or {@code null} if there was nothing to back up
     */
    private static File backupKeyFile(File keyFile) {
        if (!keyFile.exists()) return null;

        File backupsDir = new File(ConfigHelper.getLocalDataDirectory(), "backups");
        if (!backupsDir.exists() && !backupsDir.mkdirs()) {
            LOGGER.warning("[security] Failed to create backups directory: " + backupsDir.getPath());
        }

        String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
        File backup = new File(backupsDir, "key-" + timestamp + ".dat");
        if (!keyFile.renameTo(backup)) {
            LOGGER.warning("[security] Failed to move wallet to backup location");
            return null;
        }
        LOGGER.info("[security] Wallet backup created: " + backup.getPath());
        return backup;
    }

    /** Inspect the first line of a wallet file to determine its format version. */
    private static int detectWalletVersion(String firstLine) {
        if (firstLine != null && firstLine.startsWith(VERSION_HEADER)) {
            try {
                return Integer.parseInt(firstLine.substring(VERSION_HEADER.length()));
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING,
                        "[security] Unrecognised version header — treating wallet as legacy V1");
            }
        }
        return VERSION_1_SHA1;
    }

    // =========================================================================
    // Private — wallet lifecycle
    // =========================================================================

    /** Generate a fresh BIP39 seed, persist it, and return the mnemonic word list. */
    private static List<String> generateAndPersistNewSeed(char[] passphrase, File file) {
        try {
            DeterministicSeed seed = new DeterministicSeed(
                    SecureRandom.getInstanceStrong(), 128, "",
                    System.currentTimeMillis() / 1000);
            String mnemonic = Joiner.on(" ").join(
                    Objects.requireNonNull(seed.getMnemonicCode()));
            if (writeInitialData(file, mnemonic, passphrase)) {
                return seed.getMnemonicCode();
            }
            return null;
        } catch (NoSuchAlgorithmException e) {
            LOGGER.log(Level.SEVERE, "Failed to obtain strong SecureRandom", e);
            return null;
        }
    }

    /**
     * Back up any existing wallet, then encrypt and persist the mnemonic.
     *
     * @param keyFile    destination wallet file
     * @param mnemonic   space-separated mnemonic string
     * @param passphrase caller-owned; never copied to a String
     * @return {@code true} on success
     */
    private static boolean writeInitialData(File keyFile, String mnemonic, char[] passphrase) {
        backupKeyFile(keyFile);

        byte[] salt = null;
        byte[] mnemonicBytes = null;
        try {
            salt = new byte[SALT_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(salt);

            mnemonicBytes = mnemonic.getBytes(StandardCharsets.UTF_8);
            String[] parts = encryptBaseSeedWithIv(passphrase, mnemonicBytes, salt);
            writeWalletFile(keyFile, salt, parts[0], parts[1]);

            LOGGER.info("[security] Wallet created with AES-256-CBC / PBKDF2-SHA-256");
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[security] Failed to create wallet file", e);
            return false;
        } finally {
            if (salt != null) Arrays.fill(salt, (byte) 0);
            if (mnemonicBytes != null) Arrays.fill(mnemonicBytes, (byte) 0);
        }
    }

    /**
     * Re-encrypt a legacy V1 wallet in the V2 format.
     *
     * <p>Safety contract:
     * <ol>
     *   <li>The legacy file is renamed to a timestamped legacy-backup before any write.
     *   <li>After writing, the new file is verified by attempting decryption.
     *   <li>If verification fails, the new file is deleted and the legacy backup is
     *       restored. If restoration itself fails, a {@link RuntimeException} is thrown
     *       so the caller knows the wallet is in an inconsistent state.
     * </ol>
     */
    private static void migrateToNewFormat(char[] passphrase, String seed, File keyFile) {
        File legacyBackup = null;
        byte[] newSalt = null;
        byte[] seedBytes = null;

        try {
            validateMigrationReady(keyFile);

            // Create a distinctly named legacy backup (not in the backups/ subdir, so
            // it is easy to find and recover manually).
            String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
            legacyBackup = new File(ConfigHelper.getLocalDataDirectory(),
                    "key-backup-legacy-" + timestamp + ".dat");
            if (!keyFile.renameTo(legacyBackup)) {
                throw new RuntimeException("Cannot back up legacy wallet — migration aborted");
            }

            newSalt = new byte[SALT_LENGTH];
            seedBytes = seed.getBytes(StandardCharsets.UTF_8);
            SecureRandom.getInstanceStrong().nextBytes(newSalt);

            String[] parts = encryptBaseSeedWithIv(passphrase, seedBytes, newSalt);
            writeWalletFile(keyFile, newSalt, parts[0], parts[1]);

            if (!validateMigration(passphrase, keyFile)) {
                throw new RuntimeException("Post-migration validation failed — new file is unreadable");
            }

            LOGGER.log(Level.INFO, "[security] Wallet successfully migrated to V2 (SHA-256/CBC)");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[security] Migration failed: " + e.getMessage());

            // Rollback: remove the (potentially partial) new file, then restore the backup.
            keyFile.delete();
            if (legacyBackup != null && legacyBackup.exists()) {
                if (!legacyBackup.renameTo(keyFile)) {
                    throw new RuntimeException(
                            "[security] CRITICAL: migration failed AND backup restoration failed", e);
                }
                LOGGER.log(Level.INFO, "[security] Legacy wallet restored from backup");
            } else {
                throw new RuntimeException("Migration failed with no backup available", e);
            }
        } finally {
            if (newSalt != null) Arrays.fill(newSalt, (byte) 0);
            if (seedBytes != null) Arrays.fill(seedBytes, (byte) 0);
        }
    }

    /** Verify that the directory is writable before attempting migration. */
    private static void validateMigrationReady(File keyFile) {
        if (!keyFile.getParentFile().canWrite()) {
            throw new RuntimeException(
                    "No write permission on wallet directory — migration aborted");
        }
        File backupsDir = new File(ConfigHelper.getLocalDataDirectory(), "backups");
        if (!backupsDir.exists() && !backupsDir.mkdirs()) {
            throw new RuntimeException("Cannot create backup directory — migration aborted");
        }
    }

    /**
     * Verify that the migrated wallet can be decrypted and yields a valid BIP39
     * word count (12, 15, 18, 21, or 24).
     */
    private static boolean validateMigration(char[] passphrase, File keyFile) {
        try {
            WalletData data = readWalletFile(keyFile);
            String decrypted = decryptSeedCbc(passphrase, data.encrypted, data.salt, data.iv);
            int wordCount = decrypted.trim().split("\\s+").length;
            boolean valid = wordCount == 12 || wordCount == 15 || wordCount == 18
                    || wordCount == 21 || wordCount == 24;
            if (!valid) {
                LOGGER.log(Level.WARNING,
                        "[security] Migration validation: unexpected word count " + wordCount);
            }
            return valid;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[security] Migration validation failed: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // WalletData — immutable value holder
    // =========================================================================

    private static final class WalletData {
        final int version;
        final byte[] salt;
        final byte[] iv;        // null for V1 (ECB) wallets
        final byte[] encrypted;

        WalletData(int version, byte[] salt, byte[] iv, byte[] encrypted) {
            this.version = version;
            this.salt = salt;
            this.iv = iv;
            this.encrypted = encrypted;
        }
    }
}