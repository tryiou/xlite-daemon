package io.cloudchains.app;

import io.cloudchains.app.console.ConsoleMenu;
import io.cloudchains.app.net.api.JSONRPCController;
import io.cloudchains.app.net.api.JSONRPCMasterServer;
import io.cloudchains.app.net.api.http.client.EXRServerPool;
import io.cloudchains.app.net.api.http.client.HTTPClient;
import io.cloudchains.app.util.CCLogger;
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

    private static final boolean isLoggingEnabled = false;
    // DEBUG ENDPOINT
    public static String BASE_URL = "https://xliterevp.mywire.org/";
    // "http://xl-dae-prox.airdns.org:42111/";
    // DEBUG ENDPOINT
    public static String EXR_ENDPOINT = null;
    public static EXRServerPool exrServerPool = null;
    public static HTTPClient feeUpdateHttpClient = new HTTPClient(2);
    public static HTTPClient heightUpdateHttpClient = new HTTPClient(2);
    public static JSONRPCMasterServer masterRPC = JSONRPCController.getMasterServer();
    public static ConsoleMenu console = null;
    public static Dotenv dotenv = null;

    public static String getEnv(String key) {
        if (dotenv == null) {
            try {
                dotenv = Dotenv.configure().ignoreIfMissing().load();
            } catch (Exception ignored) {
            }
        }
        if (dotenv != null) {
            String value = dotenv.get(key);
            if (value != null) return value;
        }
        return System.getenv(key);
    }

    public static void initExrEndpoint() {
        if (EXR_ENDPOINT != null) return;
        String exrEndpoint = getEnv("EXR_ENDPOINT");
        if (exrEndpoint != null && !exrEndpoint.isEmpty()) {
            EXR_ENDPOINT = exrEndpoint;
            exrServerPool = new EXRServerPool(EXR_ENDPOINT);
            LOGGER.log(Level.INFO, "[app] EXR mode enabled with " + exrServerPool.getServerCount() + " servers: " + EXR_ENDPOINT);
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

        CCLogger.setLogging(isLoggingEnabled);
        LOGGER.setLevel(Level.INFO);
        LOGGER.setUseParentHandlers(false);

        // Perform log rotation before initializing other components
        LogRotationUtil.performLogRotation();

        Runtime.getRuntime().addShutdownHook(new Thread(App::shutdown));

        try {
            String userHomeDir;
            String OS = (System.getProperty("os.name")).toLowerCase();

            if (OS.contains("win")) {
                userHomeDir = getEnv("AppData");
            } else if (OS.contains("nix") || OS.contains("nux") || OS.contains("aix")) {
                userHomeDir = System.getProperty("user.home") + File.separator + ".config";
            } else if (OS.contains("mac")) {
                userHomeDir = System.getProperty("user.home") + File.separator + "Library" + File.separator + "Application Support";
            } else {
                userHomeDir = System.getProperty("user.home") + File.separator + ".config";
            }

            DateTimeFormatter timeStampPattern = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            Handler fileHandler = new FileHandler(
                    userHomeDir +
                            File.separator +
                            "CloudChains" +
                            File.separator +
                            "error-" +
                            timeStampPattern.format(LocalDateTime.now()) +
                            ".log",
                    true
            );

            fileHandler.setFormatter(new FileFormatter());
            fileHandler.setLevel(Level.INFO);

            LOGGER.addHandler(fileHandler);

        } catch (IOException e) {
            // TODO Auto-generated catch block
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
