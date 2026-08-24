package io.cloudchains.app;

import io.cloudchains.app.console.ConsoleMenu;
import io.cloudchains.app.net.api.JSONRPCController;
import io.cloudchains.app.net.api.JSONRPCMasterServer;
import io.cloudchains.app.net.api.http.client.EXRServerPool;
import io.cloudchains.app.net.api.http.client.HTTPClient;
import io.cloudchains.app.util.ConsoleFormatter;
import io.cloudchains.app.util.FileFormatter;
import io.cloudchains.app.util.LogRotationUtil;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.*;

public class App {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    // DEBUG ENDPOINT
    public static String BASE_URL = "https://xliterevp.mywire.org/";
    // "http://xl-dae-prox.airdns.org:42111/";
    // DEBUG ENDPOINT
    public static volatile String EXR_ENDPOINT = null;
    public static volatile EXRServerPool exrServerPool = null;
    public static HTTPClient feeUpdateHttpClient = new HTTPClient(2);
    public static HTTPClient heightUpdateHttpClient = new HTTPClient(2);
    public static JSONRPCMasterServer masterRPC = JSONRPCController.getMasterServer();
    public static ConsoleMenu console = null;
    public static Dotenv dotenv = null;

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
            if (value != null) return value;
        }
        return System.getenv(key);
    }

    public static String getUserConfigDir() {
        String OS = (System.getProperty("os.name")).toLowerCase();
        if (OS.contains("win")) {
            return getEnv("AppData");
        } else if (OS.contains("nix") || OS.contains("nux") || OS.contains("aix")) {
            return System.getProperty("user.home") + File.separator + ".config";
        } else if (OS.contains("mac")) {
            return System.getProperty("user.home") + File.separator + "Library" + File.separator + "Application Support";
        }
        return System.getProperty("user.home") + File.separator + ".config";
    }

    private static Level parseLogLevel(String envValue, Level defaultLevel) {
        if (envValue == null || envValue.trim().isEmpty()) {
            return defaultLevel;
        }
        try {
            return Level.parse(envValue.trim().toUpperCase());
        } catch (Exception e) {
            LOGGER.warning("[app] Invalid log level '" + envValue + "', using default " + defaultLevel);
            return defaultLevel;
        }
    }

    public static void initExrEndpoint() {
        if (EXR_ENDPOINT != null) return;
        String exrEndpoint = getEnv("EXR_ENDPOINT");
        if (exrEndpoint != null && !exrEndpoint.isEmpty()) {
            EXR_ENDPOINT = exrEndpoint;
            exrServerPool = new EXRServerPool(EXR_ENDPOINT);
            LOGGER.info("[app] EXR mode enabled with " + exrServerPool.getServerCount() + " servers: " + EXR_ENDPOINT);
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

        initExrEndpoint();

        Level logLevel = parseLogLevel(getEnv("CLOUDCHAINS_LOG_LEVEL"), Level.INFO);
        LOGGER.setLevel(logLevel);
        LOGGER.setUseParentHandlers(false);

        LogRotationUtil.performLogRotation();

        Runtime.getRuntime().addShutdownHook(new Thread(App::shutdown));

        try {
            String userHomeDir = getUserConfigDir();
            String logDir = userHomeDir + File.separator + "CloudChains";
            DateTimeFormatter timeStampPattern = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            Handler fileHandler = new FileHandler(
                    logDir + File.separator + "error-" + timeStampPattern.format(LocalDateTime.now()) + ".log",
                    10_000_000,  // max file size 10MB
                    5,          // 5 rotated files
                    true         // append to existing
            );

            fileHandler.setFormatter(new FileFormatter());
            fileHandler.setLevel(Level.INFO);

            LOGGER.addHandler(fileHandler);

        } catch (IOException e) {
            LOGGER.warning("[app] Failed to initialize file handler: " + e.getMessage());
        }

        ConsoleHandler consoleHandler = new ConsoleHandler(){
            @Override
            protected synchronized void setOutputStream(OutputStream out) throws SecurityException {
                super.setOutputStream(System.out);
            }
        };
        consoleHandler.setFormatter(new ConsoleFormatter());
        consoleHandler.setLevel(Level.FINE);

        LOGGER.addHandler(consoleHandler);

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
        if (masterRPC != null && masterRPC.isAlive()) {
            System.out.println("Shutting down...");
        }

        if (console != null) {
            console.deinit();
        }

        if (feeUpdateHttpClient != null) {
            feeUpdateHttpClient.close();
        }

        if (heightUpdateHttpClient != null) {
            heightUpdateHttpClient.close();
        }

        if (exrServerPool != null) {
            exrServerPool.close();
        }

        if (masterRPC != null) {
            masterRPC.deinit();
        }

        for (Handler handler : LOGGER.getHandlers()) {
            LOGGER.removeHandler(handler);
            handler.close();
        }
    }
}
