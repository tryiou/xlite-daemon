package io.cloudchains.app.crypto;

import com.google.common.base.Joiner;
import com.subgraph.orchid.encoders.Base64;
import io.cloudchains.app.util.ConfigHelper;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.wallet.DeterministicSeed;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;


public class KeyHandler {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);


    // Version constants for wallet migration
    private static final int VERSION_1_SHA1 = 1;      // Legacy SHA-1 format
    private static final int VERSION_2_SHA256 = 2;    // Current SHA-256 format
    private static final int CURRENT_VERSION = VERSION_2_SHA256;
    private static final String VERSION_HEADER = "VERSION:";

    // Legacy format marker for backward compatibility
    private static final String LEGACY_SALT_MARKER = "legacySalt";

    // PBKDF2 parameters for secure key derivation
    private static final int PBKDF2_ITERATIONS_SHA256 = 100000;  // Current secure iteration count
    private static final int PBKDF2_ITERATIONS_SHA1 = 16384;     // Legacy iteration count
    private static final int KEY_LENGTH = 256;                   // AES key length in bits
    private static final int SALT_LENGTH = 20;                   // Salt length in bytes
    
    // PBKDF2 algorithms
    private static final String PBKDF2_ALGORITHM_SHA256 = "PBKDF2WithHmacSHA256";
    private static final String PBKDF2_ALGORITHM_SHA1 = "PBKDF2WithHmacSHA1";
    private static final String CIPHER_ALGORITHM = "AES";

    private ECKey ecKey;

    /**
     * Create a KeyHandler with the specified ECKey.
     *
     * @param key the ECKey to handle
     */
    public KeyHandler(ECKey key) {
        this.ecKey = key;
    }

    /**
     * Get the base ECKey.
     *
     * @return the base ECKey
     */
    public ECKey getBaseECKey() {
        return this.ecKey;
    }

    /**
     * Get the public key derived from the base ECKey.
     *
     * @return the public key
     */
    public ECKey getPublicKey() {
        return ECKey.fromPublicOnly(this.ecKey.getPubKey());
    }


    /**
     * Check if a wallet file exists locally.
     *
     * @return true if a wallet file exists, false otherwise
     */
    public static boolean existsBaseECKeyFromLocal() {
        String keyPath = ConfigHelper.getLocalDataDirectory() + "key.dat";
        File keyFile = new File(keyPath);
        return keyFile.exists();
    }

    /**
     * Encrypt base seed using specified PBKDF2 parameters.
     *
     * @param passphrase the user's passphrase
     * @param seedBytes the seed data to encrypt
     * @param salt the salt for PBKDF2
     * @param algorithm the PBKDF2 algorithm (SHA-1 or SHA-256)
     * @param iterations the number of PBKDF2 iterations
     * @return base64-encoded encrypted seed
     */
    private static String encryptBaseSeed(String passphrase, byte[] seedBytes, byte[] salt,
                                          String algorithm, int iterations) {
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance(algorithm);
            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_LENGTH);
            SecretKey tmp = skf.generateSecret(spec);
            SecretKey key = new SecretKeySpec(tmp.getEncoded(), CIPHER_ALGORITHM);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(seedBytes);
            return new String(Base64.encode(encrypted));
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt seed", e);
        }
    }

    /**
     * Encrypt base seed using current secure parameters (SHA-256, 100k iterations).
     *
     * @param passphrase the user's passphrase
     * @param seedBytes the seed data to encrypt
     * @param salt the salt for PBKDF2
     * @return base64-encoded encrypted seed
     */
    private static String encryptBaseSeed(String passphrase, byte[] seedBytes, byte[] salt) {
        return encryptBaseSeed(passphrase, seedBytes, salt,
                PBKDF2_ALGORITHM_SHA256, PBKDF2_ITERATIONS_SHA256);
    }

    /**
     * Encrypt base seed using legacy parameters (SHA-1, 16k iterations) for migration.
     *
     * @param passphrase the user's passphrase
     * @param seedBytes the seed data to encrypt
     * @param salt the salt for PBKDF2
     * @return base64-encoded encrypted seed
     */
    private static String encryptBaseSeedLegacy(String passphrase, byte[] seedBytes, byte[] salt) {
        return encryptBaseSeed(passphrase, seedBytes, salt,
                PBKDF2_ALGORITHM_SHA1, PBKDF2_ITERATIONS_SHA1);
    }

    /**
     * Get the base seed from the wallet, decrypting if necessary.
     * Handles legacy wallet migration automatically.
     *
     * @param passphrase the user's passphrase
     * @return the seed as a list of words, or null if decryption fails
     */
    public static List<String> getBaseSeed(String passphrase) {
        File keyFile = new File(ConfigHelper.getLocalDataDirectory() + "key.dat");
        BufferedReader bufferedReader;

        if (existsBaseECKeyFromLocal()) {
            byte[] salt = null;
            byte[] seedEncrypted = null;
            try {
                bufferedReader = new BufferedReader(new FileReader(keyFile));
                String firstLine = bufferedReader.readLine();
                String saltB64;
                String seedEncryptedB64;

                // Check if file has version header
                if (firstLine != null && firstLine.startsWith(VERSION_HEADER)) {
                    // New format: VERSION, salt, encrypted seed
                    saltB64 = bufferedReader.readLine();
                    seedEncryptedB64 = bufferedReader.readLine();
                } else {
                    // Legacy format: salt, encrypted seed (no version header)
                    saltB64 = firstLine;
                    seedEncryptedB64 = bufferedReader.readLine();
                }
                bufferedReader.close();

                salt = Base64.decode(saltB64);
                seedEncrypted = Base64.decode(seedEncryptedB64);

                // Detect wallet version and use appropriate decryption
                int walletVersion = detectWalletVersion(firstLine);

                String seed;
                if (walletVersion == VERSION_1_SHA1) {
                    // Legacy wallet - decrypt with SHA-1
                    seed = decryptSeedLegacy(passphrase, seedEncrypted, salt);
                    LOGGER.log(Level.INFO, "[security] Detected legacy wallet format, migrating to SHA-256...");

                    // Migrate to new format
                    migrateToNewFormat(passphrase, seed, keyFile);
                } else {
                    // Current wallet - decrypt with SHA-256
                    seed = decryptSeed(passphrase, seedEncrypted, salt);
                }

                return Arrays.asList(seed.split(" "));
            } catch (Exception e) {
                LOGGER.log(Level.FINER, "Error while obtaining base seed: " + e);
                LOGGER.log(Level.FINER, "Bad password.");
                return null;
            } finally {
                // Clear sensitive data from memory
                if (salt != null) Arrays.fill(salt, (byte) 0);
                if (seedEncrypted != null) Arrays.fill(seedEncrypted, (byte) 0);
            }
        } else {
            DeterministicSeed seed = null;
            seed = new DeterministicSeed(new SecureRandom(), 128, "", System.currentTimeMillis() / 1000);

            String mnemonic = Joiner.on(" ").join(Objects.requireNonNull(seed.getMnemonicCode()));

            if (writeInitialData(keyFile, mnemonic, passphrase)) {
                return seed.getMnemonicCode();
            } else {
                return null;
            }
        }
    }

    /**
     * Detect wallet version from salt header
     */
    private static int detectWalletVersion(String saltB64) {
        // Legacy wallets don't have version header
        if (saltB64.startsWith(VERSION_HEADER)) {
            try {
                return Integer.parseInt(saltB64.substring(VERSION_HEADER.length()));
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING, "[security] Invalid wallet version, assuming legacy format");
                return VERSION_1_SHA1;
            }
        }
        return VERSION_1_SHA1; // Default to legacy for backward compatibility
    }

    /**
     * Decrypt seed using specified PBKDF2 parameters.
     *
     * @param passphrase the user's passphrase
     * @param seedEncrypted the encrypted seed data
     * @param salt the salt for PBKDF2
     * @param algorithm the PBKDF2 algorithm (SHA-1 or SHA-256)
     * @param iterations the number of PBKDF2 iterations
     * @return decrypted seed as string
     * @throws Exception if decryption fails
     */
    private static String decryptSeed(String passphrase, byte[] seedEncrypted, byte[] salt,
                                      String algorithm, int iterations) throws Exception {
        SecretKeyFactory skf = SecretKeyFactory.getInstance(algorithm);
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_LENGTH);
        SecretKey tmp = skf.generateSecret(spec);
        SecretKey key = new SecretKeySpec(tmp.getEncoded(), CIPHER_ALGORITHM);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key);
        return new String(cipher.doFinal(seedEncrypted));
    }

    /**
     * Decrypt seed using current secure parameters (SHA-256, 100k iterations).
     *
     * @param passphrase the user's passphrase
     * @param seedEncrypted the encrypted seed data
     * @param salt the salt for PBKDF2
     * @return decrypted seed as string
     * @throws Exception if decryption fails
     */
    private static String decryptSeed(String passphrase, byte[] seedEncrypted, byte[] salt) throws Exception {
        return decryptSeed(passphrase, seedEncrypted, salt,
                PBKDF2_ALGORITHM_SHA256, PBKDF2_ITERATIONS_SHA256);
    }

    /**
     * Decrypt seed using legacy parameters (SHA-1, 16k iterations) for migration.
     *
     * @param passphrase the user's passphrase
     * @param seedEncrypted the encrypted seed data
     * @param salt the salt for PBKDF2
     * @return decrypted seed as string
     * @throws Exception if decryption fails
     */
    private static String decryptSeedLegacy(String passphrase, byte[] seedEncrypted, byte[] salt) throws Exception {
        return decryptSeed(passphrase, seedEncrypted, salt,
                PBKDF2_ALGORITHM_SHA1, PBKDF2_ITERATIONS_SHA1);
    }

    /**
     * Validate that the environment is ready for migration.
     *
     * @param keyFile the wallet file to migrate
     * @throws RuntimeException if environment is not ready for migration
     */
    private static void validateMigrationReady(File keyFile) {
        File parentDir = keyFile.getParentFile();
        if (!parentDir.canWrite()) {
            throw new RuntimeException("No write permission - migration aborted");
        }

        // Check backup directory exists
        File backupsDir = new File(ConfigHelper.getLocalDataDirectory() + "backups");
        if (!backupsDir.exists() && !backupsDir.mkdirs()) {
            throw new RuntimeException("Cannot create backup directory");
        }
    }

    /**
     * Migrate legacy wallet to new secure format with improved error handling and validation.
     *
     * @param passphrase the user's passphrase
     * @param seed the decrypted seed from legacy wallet
     * @param keyFile the wallet file to migrate
     * @throws RuntimeException if migration fails and cannot be recovered
     */
    private static void migrateToNewFormat(String passphrase, String seed, File keyFile) {
        File backupFile = null;
        byte[] newSalt = null;

        try {
            // Validate environment before migration
            validateMigrationReady(keyFile);

            // Create backup of old wallet
            backupFile = new File(ConfigHelper.getLocalDataDirectory() + "key-backup-legacy.dat");
            if (!keyFile.renameTo(backupFile)) {
                throw new RuntimeException("Cannot create backup - migration aborted for safety");
            }

            // Generate new salt with cryptographically strong random number generator
            SecureRandom secureRandom = SecureRandom.getInstanceStrong();
            newSalt = new byte[SALT_LENGTH];
            secureRandom.nextBytes(newSalt);

            // Encrypt with new secure parameters
            String encryptedSeed = encryptBaseSeed(passphrase, seed.getBytes(), newSalt);

            // Write new format with version header
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(keyFile))) {
                writer.write(VERSION_HEADER + CURRENT_VERSION);
                writer.newLine();
                writer.write(new String(Base64.encode(newSalt)));
                writer.newLine();
                writer.write(encryptedSeed);
                writer.newLine();
            }

            // Validate the migration by attempting to decrypt the new format
            if (!validateMigration(passphrase, keyFile)) {
                throw new RuntimeException("Migration validation failed - new format is unreadable");
            }

            LOGGER.log(Level.INFO, "[security] Successfully migrated wallet to SHA-256 format");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[security] Migration failed: " + e.getMessage());

            // MUST restore backup or throw critical error
            if (backupFile != null && backupFile.exists()) {
                if (!backupFile.renameTo(keyFile)) {
                    LOGGER.log(Level.SEVERE, "[security] CRITICAL: Cannot restore backup!");
                    throw new RuntimeException("Migration failed and backup restoration failed", e);
                }
                LOGGER.log(Level.INFO, "[security] Restored legacy wallet from backup");
            } else {
                throw new RuntimeException("Migration failed with no backup available", e);
            }
        } finally {
            // Clear sensitive data from memory
            if (newSalt != null) {
                Arrays.fill(newSalt, (byte) 0);
            }
        }
    }

    /**
     * Validate that the migrated wallet can be successfully decrypted.
     *
     * @param passphrase the user's passphrase
     * @param keyFile the migrated wallet file
     * @return true if validation succeeds, false otherwise
     */
    private static boolean validateMigration(String passphrase, File keyFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(keyFile))) {
            String versionLine = reader.readLine();
            String saltB64 = reader.readLine();
            String encryptedSeedB64 = reader.readLine();

            if (versionLine == null || saltB64 == null || encryptedSeedB64 == null) {
                LOGGER.log(Level.WARNING, "[security] Migration validation failed: incomplete file format");
                return false;
            }

            byte[] salt = Base64.decode(saltB64);
            byte[] encryptedSeed = Base64.decode(encryptedSeedB64);

            // Attempt to decrypt with current parameters
            String decryptedSeed = decryptSeed(passphrase, encryptedSeed, salt);

            // Basic validation that the seed is reasonable
            String[] words = decryptedSeed.split(" ");
            if (words.length < 12 || words.length > 24) {
                LOGGER.log(Level.WARNING, "[security] Migration validation failed: invalid seed length");
                return false;
            }

            return true;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[security] Migration validation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Import a wallet from a mnemonic seed phrase.
     *
     * @param mnemonicList the mnemonic seed phrase as a list of words
     * @param passphrase the user's passphrase
     * @return true if import successful, false otherwise
     */
    public static boolean importFromMnemonic(List<String> mnemonicList, String passphrase) {
        if (mnemonicList == null || mnemonicList.isEmpty()) {
            throw new IllegalArgumentException("Mnemonic list cannot be null or empty");
        }
        File keyFile = new File(ConfigHelper.getLocalDataDirectory() + "key.dat");
        byte[] entropy;

        try {
            MnemonicCode mnemonicCode = new MnemonicCode();
            entropy = mnemonicCode.toEntropy(mnemonicList);
        } catch (IOException | MnemonicException.MnemonicWordException | MnemonicException.MnemonicChecksumException | MnemonicException.MnemonicLengthException e) {
            e.printStackTrace();
            return false;
        }

        DeterministicSeed seed = new DeterministicSeed(entropy, "", System.currentTimeMillis() / 1000);

        String mnemonic = Joiner.on(" ").join(Objects.requireNonNull(seed.getMnemonicCode()));

        if (!seed.getMnemonicCode().toString().equals(mnemonicList.toString()))
            return false;

        return writeInitialData(keyFile, mnemonic, passphrase);
    }

    /**
     * Convert a mnemonic seed phrase to entropy bytes.
     *
     * @param mnemonicList the mnemonic seed phrase as a list of words
     * @return the entropy bytes, or null if conversion fails
     */
    public static byte[] mnemonicToEntropy(List<String> mnemonicList) {
        byte[] entropy = null;

        try {
            MnemonicCode mnemonicCode = new MnemonicCode();
            entropy = mnemonicCode.toEntropy(mnemonicList);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return entropy;
    }

    private static File findRenameFile() {
        for (int i = 0; i < 100; i++) {
            File file = new File(ConfigHelper.getLocalDataDirectory() + "key-" + i + ".dat");

            if (!file.exists()) {
                return file;
            }
        }

        return null;
    }

    /**
     * Write initial wallet data with secure parameters and proper error handling.
     *
     * @param keyFile the wallet file to write
     * @param mnemonic the mnemonic seed phrase
     * @param passphrase the user's passphrase
     * @return true if successful, false otherwise
     */
    private static boolean writeInitialData(File keyFile, String mnemonic, String passphrase) {
        // Move current wallet file to backups
        if (keyFile.exists()) {
            // Backups dir
            String backups = ConfigHelper.getLocalDataDirectory() + "backups" + File.separator;
            File backupsDir = new File(backups);
            if (!backupsDir.exists() && !backupsDir.mkdir()) {
                LOGGER.warning("Failed to create backups dir " + backupsDir.getPath());
            }

            Date date = new Date();
            SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
            String newFileName = backups + "key-" + formatter.format(date) + ".dat";

            File oldFile = new File(keyFile.getPath());
            if (!oldFile.renameTo(new File(newFileName))) {
                LOGGER.info("Failed to rename old wallet file");
            } else {
                LOGGER.info("Created wallet backup " + newFileName);
            }
        }

        // Generate cryptographically strong salt
        byte[] salt = null;
        try {
            SecureRandom secureRandom = SecureRandom.getInstanceStrong();
            salt = new byte[SALT_LENGTH];
            secureRandom.nextBytes(salt);

            byte[] mnemonicBytes = mnemonic.getBytes();
            String encryptedSeed = encryptBaseSeed(passphrase, mnemonicBytes, salt);

            if (encryptedSeed == null) {
                LOGGER.severe("[security] Failed to encrypt seed during wallet creation");
                return false;
            }

            // Write wallet file with version header
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(keyFile))) {
                writer.write(VERSION_HEADER + CURRENT_VERSION);
                writer.newLine();
                writer.write(new String(Base64.encode(salt)));
                writer.newLine();
                writer.write(encryptedSeed);
                writer.newLine();
            }

            LOGGER.info("[security] Successfully created new wallet with SHA-256 encryption");
            return true;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[security] Failed to create wallet file", e);
            return false;
        } finally {
            // Clear sensitive data from memory
            if (salt != null) {
                Arrays.fill(salt, (byte) 0);
            }
        }
    }

    /**
     * Parse a mnemonic seed phrase string into a list of words.
     *
     * @param mnemonic the mnemonic seed phrase as a string
     * @return the mnemonic as a list of words
     */
    public static List<String> getMnemonicFromString(String mnemonic) {
        return Arrays.asList(mnemonic.split(" "));
    }

    /**
     * Calculate the strength score of a password.
     *
     * Scoring:
     * - 8-9 characters: 1 point
     * - 10+ characters: 2 points
     * - Contains digit: +2 points
     * - Contains lowercase letter: +2 points
     * - Contains uppercase letter: +2 points
     * - Contains special character: +2 points
     *
     * @param password the password to evaluate
     * @return the strength score (0-10)
     */
    public static int calculatePasswordStrength(String password) {
        // Password must be greater than 8 characters, contain at least one digit, one lowercase letter, one uppercase letter and one special character.

        int totalScore = 0;

        if (password.length() < 8) {
            return 0;
        } else if (password.length() >= 10) {
            totalScore += 2;
        } else {
            totalScore += 1; // 8-9 characters gets 1 point
        }

        //if it contains one digit, add 2 to total score
        if (password.matches("(?=.*[0-9]).*"))
            totalScore += 2;

        //if it contains one lower case letter, add 2 to total score
        if (password.matches("(?=.*[a-z]).*"))
            totalScore += 2;

        //if it contains one upper case letter, add 2 to total score
        if (password.matches("(?=.*[A-Z]).*"))
            totalScore += 2;

        //if it contains one special character, add 2 to total score
        if (password.matches("(?=.*[~!@#$%^&*()_-]).*"))
            totalScore += 2;

        return totalScore;
    }
}
