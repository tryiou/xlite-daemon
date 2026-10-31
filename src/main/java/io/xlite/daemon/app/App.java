package io.xlite.daemon.app;

import io.github.cdimascio.dotenv.Dotenv;
import io.xlite.daemon.app.console.ConsoleMenu;
import io.xlite.daemon.app.net.api.JSONRPCController;
import io.xlite.daemon.app.net.api.JSONRPCMasterServer;
import io.xlite.daemon.app.net.api.http.client.EXRServerPool;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;
import io.xlite.daemon.app.util.ConfigHelper;
import io.xlite.daemon.app.util.ConsoleFormatter;
import io.xlite.daemon.app.util.FileFormatter;
import io.xlite.daemon.app.util.LogRotationUtil;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public final class App {
    private App() {}

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    /** Upstream used when no EXR pool is configured. */
    private static volatile String baseUrl = "https://xliterevp.mywire.org/";

    private static volatile String exrEndpoint = null;
    private static volatile EXRServerPool exrServerPool = null;

    private static final HTTPClient FEE_UPDATE_HTTP_CLIENT = new HTTPClient(2);
    private static final HTTPClient HEIGHT_UPDATE_HTTP_CLIENT = new HTTPClient(2);
    private static final JSONRPCMasterServer MASTER_RPC = JSONRPCController.getMasterServer();

    private static ConsoleMenu console = null;
    private static Dotenv dotenv = null;

    public static String getBaseUrl() {
        return baseUrl;
    }

    public static void setBaseUrl(String url) {
        baseUrl = url;
    }

    public static String getExrEndpoint() {
        return exrEndpoint;
    }

    public static void setExrEndpoint(String endpoint) {
        exrEndpoint = endpoint;
    }

    public static EXRServerPool getExrServerPool() {
        return exrServerPool;
    }

    public static void setExrServerPool(EXRServerPool pool) {
        exrServerPool = pool;
    }

    public static HTTPClient getFeeUpdateHttpClient() {
        return FEE_UPDATE_HTTP_CLIENT;
    }

    public static HTTPClient getHeightUpdateHttpClient() {
        return HEIGHT_UPDATE_HTTP_CLIENT;
    }

    public static JSONRPCMasterServer getMasterRpc() {
        return MASTER_RPC;
    }

    public static String getEnv(String key) {
        if (dotenv == null) {
            try {
                dotenv = Dotenv.configure().ignoreIfMissing().load();
            } catch (Exception e) {
                LOGGER.finer("[app] No .env file found or failed to load" + e.getMessage());
            }
        }
        if (dotenv != null) {
            String value = dotenv.get(key);
            if (value != null) {
                return value;
            }
        }
        return System.getenv(key);
    }

    /**
     * Resolves the platform application-data root shared by the XLite apps.
     *
     * <p>Precedence: the {@code XLITE_DATA_HOME} environment variable (when non-blank, normalized
     * to an absolute path) over the per-OS default. Pure function so the precedence matrix stays
     * unit-testable.
     *
     * @param dataHomeEnv value of {@code XLITE_DATA_HOME} (may be null)
     * @param osName value of the {@code os.name} system property
     * @param userHome value of the {@code user.home} system property (must be non-blank on
     *     mac/Linux)
     * @param appDataEnv Windows {@code AppData} environment value (must be non-blank on Windows)
     * @return the resolved config-root directory string
     * @throws IllegalStateException when no override is set and the platform default is missing
     */
    public static String resolveUserConfigDir(
            String dataHomeEnv, String osName, String userHome, String appDataEnv) {
        if (dataHomeEnv != null && !dataHomeEnv.trim().isEmpty()) {
            return new File(dataHomeEnv.trim()).getAbsoluteFile().getPath();
        }
        String platform = osName.toLowerCase(Locale.ROOT);
        if (platform.contains("win")) {
            // Fail fast: returning a null AppData would stringify into a
            // bogus relative "null/xlite-daemon/" dir and fork the wallet.
            if (appDataEnv == null || appDataEnv.trim().isEmpty()) {
                throw new IllegalStateException(
                        "[app] Cannot resolve config dir on Windows: AppData is not set. "
                                + "Set XLITE_DATA_HOME to an explicit path.");
            }
            return appDataEnv;
        } else if (platform.contains("mac")) {
            if (userHome == null || userHome.trim().isEmpty()) {
                throw new IllegalStateException(
                        "[app] Cannot resolve config dir on macOS: user.home is not set. "
                                + "Set XLITE_DATA_HOME to an explicit path.");
            }
            return userHome + File.separator + "Library" + File.separator + "Application Support";
        }
        if (userHome == null || userHome.trim().isEmpty()) {
            throw new IllegalStateException(
                    "[app] Cannot resolve config dir: user.home is not set. "
                            + "Set XLITE_DATA_HOME to an explicit path.");
        }
        return userHome + File.separator + ".config";
    }

    public static String getUserConfigDir() {
        return resolveUserConfigDir(
                getEnv("XLITE_DATA_HOME"),
                System.getProperty("os.name"),
                System.getProperty("user.home"),
                getEnv("AppData"));
    }

    private static Level parseLogLevel(String envValue, Level defaultLevel) {
        if (envValue == null || envValue.trim().isEmpty()) {
            return defaultLevel;
        }
        try {
            return Level.parse(envValue.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            LOGGER.warning(
                    "[app] Invalid log level '" + envValue + "', using default " + defaultLevel);
            return defaultLevel;
        }
    }

    public static void initExrEndpoint() {
        if (exrEndpoint != null) {
            return;
        }
        String endpoint = getEnv("EXR_ENDPOINT");
        if (endpoint != null && !endpoint.isEmpty()) {
            exrEndpoint = endpoint;
            exrServerPool = new EXRServerPool(endpoint);
            LOGGER.info(
                    "[app] EXR mode enabled with "
                            + exrServerPool.getServerCount()
                            + " servers: "
                            + endpoint);
        }
    }

    public static void initCoinConfigs(String[] args) {
        String flagValue = null;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if ("--blockchain-configuration-files".equals(args[i])) {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        flagValue = args[i + 1];
                    } else {
                        LOGGER.warning(
                                "[coinconfig] Missing value after"
                                        + " --blockchain-configuration-files");
                    }
                    break;
                }
            }
        }
        String envValue = getEnv("BLOCKCHAIN_CONFIGURATION_FILES");
        String source;
        try {
            source =
                    new io.xlite.daemon.app.coinconfig.ConfigSourceResolver(flagValue, envValue)
                            .resolve();
        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING, "[coinconfig] invalid source, using default: " + e.getMessage());
            source = io.xlite.daemon.app.coinconfig.ConfigSourceResolver.DEFAULT_UPSTREAM;
        }
        try {
            io.xlite.daemon.app.coinconfig.CoinConfigRegistry.loadWithFallback(
                    source, cacheFor(source));
        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING,
                    "[coinconfig] failed to load from "
                            + source
                            + ": "
                            + e.getMessage()
                            + " — continuing with no configs; migrated coins will report"
                            + " UNSUPPORTEDCOIN");
        }
    }

    /**
     * Write-through cache for remote config sources, rooted in the daemon data dir. Local checkouts
     * bypass the cache; any failure resolving it degrades to uncached (a null cache loads exactly
     * as before).
     */
    private static io.xlite.daemon.app.coinconfig.CoinConfigCache cacheFor(String source) {
        try {
            if (io.xlite.daemon.app.coinconfig.ConfigSourceResolver.isLocalDirectory(source)) {
                return null;
            }
            java.nio.file.Path root =
                    java.nio.file.Paths.get(
                            getUserConfigDir(), ConfigHelper.DATA_DIR_NAME, "coinconfig-cache");
            return io.xlite.daemon.app.coinconfig.CoinConfigCache.forSource(root, source);
        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING,
                    "[coinconfig] cache unavailable: " + e.getMessage() + " — continuing uncached");
            return null;
        }
    }

    public static void main(String[] args) {
        for (String arg : args) {
            if (arg.equals("--version")) {
                System.out.println(Version.CLIENT_VERSION);
                System.exit(0);
            }
            if (arg.equals("--help")) {
                System.out.println(ConsoleMenu.getHelpText());
                System.exit(0);
            }
        }

        Level logLevel = parseLogLevel(getEnv("XLITE_DAEMON_LOG_LEVEL"), Level.INFO);
        LOGGER.setLevel(logLevel);
        LOGGER.setUseParentHandlers(false);

        LogRotationUtil.performLogRotation();

        Runtime.getRuntime().addShutdownHook(new Thread(App::shutdown));

        try {
            String userHomeDir = getUserConfigDir();
            String logDir = userHomeDir + File.separator + ConfigHelper.DATA_DIR_NAME;
            DateTimeFormatter timeStampPattern = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            Handler fileHandler =
                    new FileHandler(
                            logDir
                                    + File.separator
                                    + "error-"
                                    + timeStampPattern.format(
                                            LocalDateTime.now(ZoneId.systemDefault()))
                                    + ".log",
                            10_000_000, // max file size 10MB
                            5, // 5 rotated files
                            true // append to existing
                            );

            fileHandler.setFormatter(new FileFormatter());
            fileHandler.setLevel(Level.INFO);

            LOGGER.addHandler(fileHandler);

        } catch (IOException e) {
            LOGGER.warning("[app] Failed to initialize file handler: " + e.getMessage());
        } catch (IllegalStateException e) {
            // Fail-fast config errors (unset AppData/user.home, unusable data
            // dir) abort startup with a clean message and nonzero status
            // instead of an uncaught stack trace.
            LOGGER.severe("[app] " + e.getMessage());
            System.exit(1);
        }

        // Diagnostics to stderr, payloads and prompts to stdout.
        ConsoleHandler consoleHandler =
                new ConsoleHandler() {
                    @Override
                    protected synchronized void setOutputStream(OutputStream out)
                            throws SecurityException {
                        super.setOutputStream(System.err);
                    }
                };
        consoleHandler.setFormatter(new ConsoleFormatter());
        consoleHandler.setLevel(Level.FINE);

        LOGGER.addHandler(consoleHandler);

        initExrEndpoint();
        initCoinConfigs(args);

        console = new ConsoleMenu(args);
        console.init();
    }

    public static void shutdown() {
        // First-line trace: proves hook entry even if anything below dies.
        LOGGER.info("[shutdown] hook entered");
        try {
            shutdownInner();
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "[shutdown] hook failed", t);
        }
    }

    private static void shutdownInner() {
        if (MASTER_RPC.isAlive()) {
            System.out.println("Shutting down...");
        }

        if (console != null) {
            console.deinit();
        }

        FEE_UPDATE_HTTP_CLIENT.close();
        HEIGHT_UPDATE_HTTP_CLIENT.close();

        if (exrServerPool != null) {
            exrServerPool.close();
        }

        MASTER_RPC.deinit();

        // Every listener is retired by now; release the worker group they
        // all borrowed. Bounded so a wedged event loop cannot stall exit
        // (the threads are daemons either way).
        JSONRPCController.shutdownEventLoops(2000);

        for (Handler handler : LOGGER.getHandlers()) {
            LOGGER.removeHandler(handler);
            handler.close();
        }
    }
}
