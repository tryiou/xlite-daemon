package io.cloudchains.app.util;

import com.google.common.base.Preconditions;
import io.cloudchains.app.App;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class ConfigHelper {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private String tickerStr;
    private File file;

    private long feePerByte;
    private long minTxFee;
    private boolean rpcEnabled;
    private String rpcUsername;
    private String rpcPassword;
    private int rpcPort;
    private int addressCount;

    // Override specific configuration directory (useful in unit tests)
    public static String CONFIG_DIR = ""; // Must not end with [/], e.g. /home/user/.config, not /home/user/.config/

    public ConfigHelper(String tickerStr) {
        this.tickerStr = Preconditions.checkNotNull(tickerStr, "tickerStr must not be null");

        try {
            file = Preconditions.checkNotNull(this.getFile());
            loadConfig();
        } catch (Exception e) {
            LOGGER.warning("[config] Failed to initialize config for " + tickerStr + ", " + e.getMessage());
        }
    }

    public synchronized void loadConfig() {
        try {
            String rawConfig = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (rawConfig.isEmpty()) {
                rpcEnabled = false;
                rpcUsername = "";
                rpcPassword = "";
                rpcPort = defaultRpcPort();
                addressCount = 0;

                writeConfig();
                return;
            }

            JSONObject config = new JSONObject(rawConfig);

            final String[] configKeys = new String[]{
                    "feeperbyte",
                    "mintxfee",
                    "rpcEnabled",
                    "rpcUsername",
                    "rpcPassword",
                    "rpcPort",
                    "addressCount"
            };

            for (String configKey : configKeys) {
                if (!config.has(configKey)) {
                    LOGGER.finer("[config] Missing config key '" + configKey + "' for " + tickerStr + ", will use default");
                }
            }

            boolean needsWrite = false;

            if (!config.has("feeperbyte")) {
                needsWrite = true;
            } else {
                feePerByte = config.getLong("feeperbyte");
            }

            if (!config.has("mintxfee")) {
                needsWrite = true;
            } else {
                minTxFee = config.getLong("mintxfee");
            }

            if (!config.has("rpcEnabled")) {
                rpcEnabled = false;
                needsWrite = true;
            } else {
                rpcEnabled = config.getBoolean("rpcEnabled");
            }

            if (!config.has("rpcUsername")) {
                rpcUsername = "";
                needsWrite = true;
            } else {
                rpcUsername = config.getString("rpcUsername");
            }

            if (!config.has("rpcPassword")) {
                rpcPassword = "";
                needsWrite = true;
            } else {
                rpcPassword = config.getString("rpcPassword");
            }

            if (!config.has("rpcPort")) {
                rpcPort = defaultRpcPort();
                needsWrite = true;
            } else {
                rpcPort = config.getInt("rpcPort");
                if (rpcPort == 0) {
                    rpcPort = -1000;
                    needsWrite = true;
                }
            }

            if (!config.has("addressCount")) {
                setAddressCount(0);
                needsWrite = true;
            } else {
                addressCount = config.getInt("addressCount");
            }

            if (needsWrite) {
                writeConfig();
            }
        } catch (Exception e) {
            LOGGER.warning("[config] Error reading config file for " + tickerStr + ", " + e.getMessage());
        }
    }

    private File getFile() {
        String userHome = getLocalDataDirectory();
        Preconditions.checkNotNull(userHome);

        File home = new File(userHome);
        File settingsDirectory = new File(home, "settings");
        if (!settingsDirectory.exists()) {
            if (!settingsDirectory.mkdirs()) {
                LOGGER.finer("[config] ERROR: Could not create base/settings directory!");
                return null;
            }
        }

        File configFile = new File(settingsDirectory, "config-" + tickerStr + ".json");
        try {
            if (!configFile.createNewFile() && !configFile.exists())
                return null;
        } catch (IOException e) {
            LOGGER.warning("[config] IOException creating config file for " + tickerStr + ", " + e.getMessage());
        }

        return configFile;
    }

    public synchronized void setFeePerByte(long feePerByte) {
        this.feePerByte = feePerByte;
    }

    public synchronized void setMinTxFee(long minTxFee) {
        this.minTxFee = minTxFee;
    }

    public synchronized void setRpcEnabled(boolean isEnabled) {
        this.rpcEnabled = isEnabled;
    }

    public synchronized void setRpcUsername(String user) {
        this.rpcUsername = user;
    }

    public synchronized void setRpcPassword(String pass) {
        this.rpcPassword = pass;
    }

    public synchronized boolean setRpcPort(int rpcPort) {
        if (rpcPort < 1 || rpcPort > 65535) {
            LOGGER.warning("[config] Invalid port " + rpcPort + ", must be 1-65535");
            return false;
        }
        int maxAttempts = 100;
        for (int i = 0; i < maxAttempts && rpcPort + i <= 65535; i++) {
            if (PortCheck.available(rpcPort + i)) {
                this.rpcPort = rpcPort + i;
                return true;
            }
        }
        LOGGER.warning("[config] No available port in range " + rpcPort + "-" + Math.min(rpcPort + maxAttempts - 1, 65535));
        return false;
    }

    public synchronized void setAddressCount(int addressCount) {
        this.addressCount = addressCount;
    }

    public synchronized long getFeePerByte() {
        return feePerByte;
    }

    public synchronized long getMinTxFee() {
        return minTxFee;
    }

    public synchronized boolean isRpcEnabled() {
        return rpcEnabled;
    }

    public synchronized String getRpcUsername() {
        return rpcUsername;
    }

    public synchronized String getRpcPassword() {
        return rpcPassword;
    }

    private int defaultRpcPort() {
        return this.tickerStr.equalsIgnoreCase("master") ? 9955 : -1000;
    }

    public synchronized int getMasterRpcPort() {
        if (rpcPort == -1000) {
            return 9955;
        }
        return rpcPort;
    }

    public synchronized int getRpcPort() {
        return rpcPort;
    }

    public synchronized int getAddressCount() {
        return addressCount;
    }

    private JSONObject toConfigJson() {
        JSONObject config = new JSONObject();
        config.put("feeperbyte", feePerByte);
        config.put("mintxfee", minTxFee);
        config.put("rpcEnabled", rpcEnabled);
        config.put("rpcUsername", rpcUsername);
        config.put("rpcPassword", rpcPassword);
        config.put("rpcPort", rpcPort);
        config.put("addressCount", addressCount);
        return config;
    }

    public synchronized boolean validAuth() {
        return rpcUsername != null && !rpcUsername.isEmpty() && rpcPassword != null && !rpcPassword.isEmpty();
    }

    public synchronized void writeConfig() {
        try {
            String newContent = toConfigJson().toString(4);

            if (file.exists()) {
                String existingContent = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (existingContent.equals(newContent)) {
                    return;
                }
            }

            try (FileWriter fw = new FileWriter(file, false)) {
                fw.write(newContent);
            }
        } catch (IOException e) {
            LOGGER.warning("[config] IOException writing config for " + tickerStr + ", " + e.getMessage());
        }
    }

    public static String getLocalDataDirectory() {
        String baseDir;
        if (CONFIG_DIR.isEmpty()) {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                baseDir = App.getEnv("AppData");
            } else if (os.contains("mac")) {
                baseDir = System.getProperty("user.home") + File.separator + "Library" + File.separator + "Application Support";
            } else {
                baseDir = System.getProperty("user.home") + File.separator + ".config";
            }
        } else {
            baseDir = CONFIG_DIR;
        }

        String userHomeDir = baseDir + File.separator + "CloudChains" + File.separator;
        File directory = new File(userHomeDir);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        return userHomeDir;
    }
}
