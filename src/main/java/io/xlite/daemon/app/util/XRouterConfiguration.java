package io.xlite.daemon.app.util;

import com.google.common.collect.HashBiMap;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class XRouterConfiguration {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    private static final int DEFAULT_CLIENT_REQUEST_LIMIT = 100;

    private final String rawXRouterConfig;
    private final HashMap<String, Double> feeMap = new HashMap<>();
    private final ArrayList<String> supportedWallets = new ArrayList<>();
    private String feeAddress;
    private int timeout;
    private int blockLimit;

    private static HashBiMap<String, Class> pluginParamTypes;

    static {
        pluginParamTypes = HashBiMap.create(2);

        pluginParamTypes.put("string", String.class);
        pluginParamTypes.put("int", Integer.class);
        pluginParamTypes.put("bool", Boolean.class);
    }

    public static String getStringByClass(Class clazz) {
        return pluginParamTypes.inverse().get(clazz);
    }

    public static class XRouterPluginConfiguration {
        private final String rawPluginConfig;
        private final String pluginName;

        private final List<Class> paramTypes = new ArrayList<>();
        private double fee;
        private int clientRequestLimit;

        public XRouterPluginConfiguration(String pluginName, String rawPluginConfig) {
            this.pluginName = pluginName;
            this.rawPluginConfig = rawPluginConfig;
        }

        public void parsePluginConfig() {
            Properties properties;
            try {
                properties = getPluginProperties(rawPluginConfig);
            } catch (IOException e) {
                LOGGER.warning(
                        "[xrouter-config] Failed to parse plugin config for "
                                + pluginName
                                + ": "
                                + e.getMessage());
                return;
            }
            applyParameters(properties);
            fee =
                    properties.containsKey("fee")
                            ? Double.parseDouble(properties.getProperty("fee"))
                            : 0;
            clientRequestLimit =
                    properties.containsKey("clientrequestlimit")
                            ? Integer.parseInt(properties.getProperty("clientrequestlimit"))
                            : DEFAULT_CLIENT_REQUEST_LIMIT;
            logParsedConfig();
        }

        private void applyParameters(Properties properties) {
            if (!properties.containsKey("parameters") && !properties.containsKey("paramsType")) {
                LOGGER.finer("[xrouter-plugin-config-parser] ERROR: Plugin has no parameters!");
                return;
            }
            String declared =
                    properties.getProperty("parameters", properties.getProperty("paramsType", ""));
            for (String rawParamType : declared.split(",")) {
                if (!rawParamType.isEmpty()) {
                    paramTypes.add(requireKnownParamType(rawParamType));
                }
            }
        }

        private static Class requireKnownParamType(String rawParamType) {
            Class<?> type = pluginParamTypes.get(rawParamType);
            if (type == null) {
                LOGGER.finer(
                        "[xrouter-plugin-config-parser] ERROR: Invalid/unsupported plugin"
                                + " parameter type: "
                                + rawParamType
                                + ". Failing.");
                throw new IllegalArgumentException(
                        "Invalid/unsupported plugin parameter type: " + rawParamType);
            }
            return type;
        }

        private void logParsedConfig() {
            LOGGER.finer(
                    "[xrouter-plugin-config-parser] Processing '" + pluginName + "' complete.");
            LOGGER.finer("[xrouter-plugin-config-parser] DEBUG: " + pluginName + ": fee = " + fee);
            LOGGER.finer("[xrouter-plugin-config-parser] DEBUG: " + pluginName + ": params = ");
            for (int i = 0; i < paramTypes.size(); i++) {
                LOGGER.finer("Parameter " + i + ":\t" + paramTypes.get(i).getSimpleName());
            }
            LOGGER.finer(
                    "[xrouter-plugin-config-parser] DEBUG: "
                            + pluginName
                            + ": clientRequestLimit = "
                            + clientRequestLimit);
        }

        private static Properties getPluginProperties(String rawConfig) throws IOException {
            String toRead = rawConfig.replace("\\n", "\n");

            Properties properties = new Properties();
            properties.load(new StringReader(toRead));
            return properties;
        }

        public String getPluginName() {
            return pluginName;
        }

        /** A read-only view of the plugin's declared parameter types. */
        public List<Class> getParamTypes() {
            return Collections.unmodifiableList(paramTypes);
        }

        public double getFee() {
            return fee;
        }

        public int getClientRequestLimit() {
            return clientRequestLimit;
        }
    }

    public XRouterConfiguration(String rawXRouterConfig) {
        this.rawXRouterConfig = rawXRouterConfig;
    }

    public void parseConfig() {
        Map<String, Properties> properties = getProperties(rawXRouterConfig);

        if (properties == null) {
            return;
        }

        LOGGER.finer("[xrouter-config-parser] DEBUG: Properties: " + properties.toString());

        Properties main = properties.get("Main");
        if (main == null) {
            LOGGER.warning("[xrouter-config] No [Main] section; XRouter stays unconfigured.");
            return;
        }

        String wallets = (String) main.get("wallets");
        if (wallets != null && !wallets.isEmpty()) {
            supportedWallets.addAll(Arrays.asList(wallets.split(",")));
        }
        timeout = parseInt(main, "timeout");
        blockLimit = parseInt(main, "blocklimit");
        feeAddress = (String) main.get("paymentaddress");

        for (Map.Entry<String, Properties> entry : properties.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("xr")) {
                double fee = Double.parseDouble((String) entry.getValue().get("fee"));
                feeMap.put(key, fee);
            }
        }

        LOGGER.finer("[xrouter-config-parser] Processing complete.");
    }

    private static int parseInt(Properties properties, String key) {
        String value = (String) properties.get(key);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warning("[xrouter-config] Invalid " + key + " value '" + value + "'");
            return 0;
        }
    }

    /** A read-only view of the wallets this node advertises. */
    public List<String> getSupportedWallets() {
        return Collections.unmodifiableList(supportedWallets);
    }

    /** A read-only view of the per-plugin fees. */
    public Map<String, Double> getFeeMap() {
        return Collections.unmodifiableMap(feeMap);
    }

    public String getFeeAddress() {
        return feeAddress;
    }

    public int getBlockLimit() {
        return blockLimit;
    }

    public int getTimeout() {
        return timeout;
    }

    /**
     * Splits an INI document into its sections and hands each body to {@link Properties#load}, so
     * escaping and continuation rules stay the JDK's. A section must open with a bracketed line;
     * text before the first header is ignored.
     */
    private static Map<String, Properties> parseINI(String toRead) throws IOException {
        Map<String, Properties> result = new LinkedHashMap<>();
        String section = null;
        StringBuilder body = new StringBuilder();

        for (String line : toRead.split("\r?\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (section != null) {
                    result.put(section, loadSection(body.toString()));
                }
                section = trimmed.substring(1, trimmed.length() - 1);
                body.setLength(0);
            } else if (section != null) {
                body.append(line).append('\n');
            }
        }
        if (section != null) {
            result.put(section, loadSection(body.toString()));
        }
        return result;
    }

    private static Properties loadSection(String body) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(body));
        return properties;
    }

    private static Map<String, Properties> getProperties(String rawConfig) {
        String formatted = rawConfig.replace("\\n", "\n");

        Map<String, Properties> properties;

        try {
            properties = parseINI(formatted);
        } catch (IOException e) {
            LOGGER.warning("[xrouter-config] Failed to parse XRouter config: " + e.getMessage());
            return null;
        }

        return properties;
    }
}
