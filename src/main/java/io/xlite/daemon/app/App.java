package io.xlite.daemon.app;

import io.github.cdimascio.dotenv.Dotenv;
import io.xlite.daemon.app.console.ConsoleMenu;
import io.xlite.daemon.app.net.api.JSONRPCController;
import io.xlite.daemon.app.net.api.JSONRPCMasterServer;
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

    /** Provider used when neither EXR_ENDPOINT nor an explicit override is configured. */
    private static volatile String baseUrl = "https://xliterevp.mywire.org/";

    /** True once BASE_URL or --development-endpoint explicitly selected the provider. */
    private static volatile boolean baseUrlExplicit = false;

    private static volatile String exrEndpoint = null;
    private static volatile String electrumEndpoint = null;

    private static JSONRPCMasterServer masterRpc = null;

    private static ConsoleMenu console = null;

    public static String getBaseUrl() {
        return baseUrl;
    }

    public static void setBaseUrl(String url) {
        baseUrl = url;
        baseUrlExplicit = true;
    }

    public static String getExrEndpoint() {
        return exrEndpoint;
    }

    public static void setExrEndpoint(String endpoint) {
        exrEndpoint = endpoint;
    }

    /** Direct native endpoint override, for testing one indexer without a node. */
    public static String getElectrumEndpoint() {
        return electrumEndpoint;
    }

    public static void setElectrumEndpoint(String endpoint) {
        electrumEndpoint = endpoint;
    }

    /**
     * The single provider endpoint this coin talks to.
     *
     * <p>Exactly one source wins: the single-indexer test override, else the EXR connector, else
     * BASE_URL. No fallback and no aggregation — a coin whose provider cannot serve it fails.
     */
    public static String resolveProviderEndpoint(String ticker) {
        String override = electrumEndpoint;
        if (override != null && !override.isBlank()) {
            return override;
        }
        String exr = exrEndpoint;
        if (exr != null && !exr.isBlank()) {
            // One connector: the first configured node is the provider, not a pool to mix.
            String first = exr.split(",")[0].trim();
            return first.isEmpty() ? null : first;
        }
        return baseUrl;
    }

    /** Refuse configurations that would mix two providers. */
    public static void validateProviderConfig() {
        boolean hasExr = exrEndpoint != null && !exrEndpoint.isBlank();
        boolean hasOverride = electrumEndpoint != null && !electrumEndpoint.isBlank();
        if (hasExr && baseUrlExplicit) {
            throw new IllegalStateException(
                    "BASE_URL and EXR_ENDPOINT are both set; configure exactly one provider");
        }
        if (hasOverride && (hasExr || baseUrlExplicit)) {
            throw new IllegalStateException(
                    "ELECTRUM_ENDPOINT is a single-indexer test override and cannot be combined"
                            + " with BASE_URL or EXR_ENDPOINT");
        }
    }

    public static synchronized JSONRPCMasterServer getMasterRpc() {
        if (masterRpc == null) {
            masterRpc = JSONRPCController.getMasterServer();
        }
        return masterRpc;
    }

    public static String getEnv(String key) {
        Dotenv file = EnvFile.INSTANCE;
        if (file != null) {
            String value = file.get(key);
            if (value != null) {
                return value;
            }
        }
        return System.getenv(key);
    }

    /**
     * Holder for the optional {@code .env} file. Class initialisation gives a thread-safe,
     * once-only load without a mutable static that callers could observe half-initialised.
     */
    private static final class EnvFile {
        private static final Dotenv INSTANCE = load();

        private static Dotenv load() {
            try {
                return Dotenv.configure().ignoreIfMissing().load();
            } catch (Exception e) {
                LOGGER.finer("[app] No .env file found or failed to load: " + e.getMessage());
                return null;
            }
        }
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
        if (isSet(dataHomeEnv)) {
            return new File(dataHomeEnv.trim()).getAbsoluteFile().getPath();
        }
        String platform = osName.toLowerCase(Locale.ROOT);
        if (platform.contains("win")) {
            return windowsConfigDir(appDataEnv);
        }
        if (platform.contains("mac")) {
            return macConfigDir(userHome);
        }
        return linuxConfigDir(userHome);
    }

    private static boolean isSet(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String windowsConfigDir(String appDataEnv) {
        // Fail fast: a null AppData would stringify into a bogus relative
        // "null/xlite-daemon/" dir and fork the wallet.
        if (!isSet(appDataEnv)) {
            throw new IllegalStateException(
                    "[app] Cannot resolve config dir on Windows: AppData is not set. "
                            + "Set XLITE_DATA_HOME to an explicit path.");
        }
        return appDataEnv;
    }

    private static String macConfigDir(String userHome) {
        if (!isSet(userHome)) {
            throw new IllegalStateException(
                    "[app] Cannot resolve config dir on macOS: user.home is not set. "
                            + "Set XLITE_DATA_HOME to an explicit path.");
        }
        return userHome + File.separator + "Library" + File.separator + "Application Support";
    }

    private static String linuxConfigDir(String userHome) {
        if (!isSet(userHome)) {
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
            LOGGER.info("[app] EXR provider enabled: " + endpoint);
        }
    }

    /**
     * Applies {@code BASE_URL} from the environment.
     *
     * <p>Without this the variable documented for the direct provider was only read when {@code
     * --development-endpoint} appeared with no value, so an environment-only deployment silently
     * kept the built-in default. It also never marked the provider explicit, which hid a mixed
     * BASE_URL + EXR_ENDPOINT configuration from {@link #validateProviderConfig()}.
     */
    public static void initBaseUrl() {
        if (applyBaseUrl(getEnv("BASE_URL"))) {
            LOGGER.info("[app] development provider enabled: " + baseUrl);
        }
    }

    /** Records an environment-supplied base URL unless a flag or an earlier source already did. */
    static boolean applyBaseUrl(String endpoint) {
        String chosen = chooseBaseUrl(baseUrlExplicit, endpoint);
        if (chosen == null) {
            return false;
        }
        setBaseUrl(chosen);
        return true;
    }

    /** Pure precedence rule: an already-committed or blank source never overrides. */
    static String chooseBaseUrl(boolean alreadyExplicit, String endpoint) {
        if (alreadyExplicit || endpoint == null || endpoint.isBlank()) {
            return null;
        }
        return endpoint.trim();
    }

    public static void initElectrumEndpoint() {
        if (electrumEndpoint != null) {
            return;
        }
        String endpoint = getEnv("ELECTRUM_ENDPOINT");
        if (endpoint != null && !endpoint.isEmpty()) {
            electrumEndpoint = endpoint;
            LOGGER.info("[app] native history enabled through " + endpoint);
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
        printImmediateArgument(args);
        installConsoleHandler();
        installFileHandler();
        configureLogging();
        initExrEndpoint();
        initBaseUrl();
        initElectrumEndpoint();
        initCoinConfigs(args);
        console = new ConsoleMenu(args);
        console.init();
    }

    /** Arguments that print and exit before any logging or wallet setup happens. */
    private static void printImmediateArgument(String[] args) {
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
    }

    private static void configureLogging() {
        Level logLevel = parseLogLevel(getEnv("XLITE_DAEMON_LOG_LEVEL"), Level.INFO);
        LOGGER.setLevel(logLevel);
        LOGGER.setUseParentHandlers(false);
        LogRotationUtil.performLogRotation();
        Runtime.getRuntime().addShutdownHook(new Thread(App::shutdown));
    }

    /** Adds the rotating error log next to the wallet data. */
    private static void installFileHandler() {
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
    }

    /** Diagnostics to stderr, payloads and prompts to stdout. */
    private static void installConsoleHandler() {
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
        JSONRPCMasterServer master = masterRpc;
        if (master != null && master.isAlive()) {
            System.out.println("Shutting down...");
        }

        if (console != null) {
            console.deinit();
        }

        if (master != null) {
            master.deinit();
        }

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
