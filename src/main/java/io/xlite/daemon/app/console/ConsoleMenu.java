package io.xlite.daemon.app.console;

import io.xlite.daemon.app.App;
import io.xlite.daemon.app.Version;
import io.xlite.daemon.app.crypto.KeyHandler;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.client.EXRServerPool;
import io.xlite.daemon.app.util.CoinConfigEnsurer;
import io.xlite.daemon.app.util.ConfigHelper;
import io.xlite.daemon.app.util.SecureTokens;
import io.xlite.daemon.app.util.background.BackgroundTimerThread;

import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ConsoleMenu {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    /** Returned by an argument handler that consumes the rest of the command line. */
    private static final int END_OF_ARGUMENTS = -1;

    /** No argument position: the secret has to be prompted for. */
    private static final int NO_ARGUMENT = -1;

    private static final Set<String> STARTUP_FLAGS =
            Set.of(
                    "--enablerpcandconfigure",
                    "--development-endpoint",
                    "--exr-endpoint",
                    "--blockchain-configuration-files",
                    "--version",
                    "--help");

    private final String[] arguments;
    private BackgroundTimerThread backgroundTimerThread = null;

    public ConsoleMenu(String[] args) {
        this.arguments = args == null ? new String[0] : args.clone();
        // Do NOT set the logger level here — App owns logging configuration
        // (XLITE_DAEMON_LOG_LEVEL); clobbering it in this constructor made all
        // FINE/FINER diagnostics unreachable.
    }

    public void logBadPassword(String msg) {
        if (msg == null || msg.isEmpty()) {
            msg = "Bad password";
        }
        LOGGER.info(
                "[master] Error("
                        + CoinInstance.CoinError.CoinErrorCode.BADPASSWORD.name()
                        + "): "
                        + msg);
    }

    public void logBadMnemonic() {
        LOGGER.info(
                "[master] Error("
                        + CoinInstance.CoinError.CoinErrorCode.BADMNEMONIC.name()
                        + "): Bad mnemonic");
    }

    public void logBadChangePass(String msg) {
        LOGGER.info(
                "[master] Error("
                        + CoinInstance.CoinError.CoinErrorCode.CHANGEPASSWORDFAILED.name()
                        + "): "
                        + msg);
    }

    public void init() {
        Scanner input = new Scanner(System.in, StandardCharsets.UTF_8);
        String newWalletStr =
                KeyHandler.existsBaseECKeyFromLocal() ? "- Disabled. Wallet already exists." : "";

        if (arguments.length > 0 && handleArguments(input)) {
            return;
        }
        if (loginFromEnvironment()) {
            return;
        }
        interactiveMenu(input, newWalletStr);
    }

    /** Handles the command-line flags. Returns true when startup is finished and no menu is due. */
    private boolean handleArguments(Scanner input) {
        for (int index = 0; index < arguments.length; index++) {
            index = handleArgument(input, index);
            if (index == END_OF_ARGUMENTS) {
                return true;
            }
        }
        return false;
    }

    private int handleArgument(Scanner input, int index) {
        String argument = arguments[index];
        if (STARTUP_FLAGS.contains(argument)) {
            return handleStartupFlag(argument, index);
        }
        return handleWalletAction(argument, input, index);
    }

    private int handleStartupFlag(String argument, int index) {
        return switch (argument) {
            case "--enablerpcandconfigure" -> {
                autoGenerateRPCConfig();
                System.exit(0);
                yield index;
            }
            case "--development-endpoint" -> setDevelopmentEndpoint(index);
            case "--exr-endpoint" -> setExrEndpoint(index);
            case "--blockchain-configuration-files" -> consumeConfigSourceValue(index);
            case "--version" -> {
                LOGGER.info(Version.CLIENT_VERSION);
                System.exit(0);
                yield index;
            }
            case "--help" -> {
                displayHelp();
                System.exit(0);
                yield index;
            }
            default -> throw new IllegalStateException("Not a startup flag: " + argument);
        };
    }

    private int handleWalletAction(String argument, Scanner input, int index) {
        return switch (argument) {
            case "--createdefaultwallet" -> {
                createDefaultWallet(input, index);
                System.exit(0);
                yield index;
            }
            case "--createwalletmnemonic" -> {
                createWalletFromMnemonic(input, index);
                System.exit(0);
                yield index;
            }
            case "--password" -> {
                loginWithPassword(input, index);
                yield END_OF_ARGUMENTS;
            }
            case "--getmnemonic" -> {
                printMnemonic(input, index);
                System.exit(0);
                yield index;
            }
            case "--changepassword" -> {
                changePassword(input, index);
                System.exit(0);
                yield index;
            }
            default -> {
                LOGGER.warning("Unrecognized argument: " + argument);
                yield index;
            }
        };
    }

    private int setDevelopmentEndpoint(int index) {
        if (index + 1 < arguments.length) {
            String customEndpoint = arguments[index + 1];
            if (customEndpoint.startsWith("--")) {
                LOGGER.warning("Invalid endpoint: " + customEndpoint);
                return index;
            }
            App.setBaseUrl(customEndpoint);
            return index + 1;
        }
        String envEndpoint = App.getEnv("BASE_URL");
        if (envEndpoint != null && !envEndpoint.isEmpty()) {
            App.setBaseUrl(envEndpoint);
        } else {
            LOGGER.warning("Missing custom endpoint after '--development-endpoint'");
        }
        return index;
    }

    private int setExrEndpoint(int index) {
        if (index + 1 < arguments.length) {
            String exrEndpoint = arguments[index + 1];
            if (exrEndpoint.startsWith("--")) {
                LOGGER.warning("Invalid endpoint: " + exrEndpoint);
                return index;
            }
            enableExr(exrEndpoint);
            return index + 1;
        }
        String envExrEndpoint = App.getEnv("EXR_ENDPOINT");
        if (envExrEndpoint != null && !envExrEndpoint.isEmpty()) {
            enableExr(envExrEndpoint);
        } else {
            LOGGER.warning("Missing EXR endpoint after '--exr-endpoint'");
        }
        return index;
    }

    private void enableExr(String endpoint) {
        App.setExrEndpoint(endpoint);
        App.setExrServerPool(new EXRServerPool(App.getExrEndpoint()));
        LOGGER.info(
                "[console] EXR mode enabled with "
                        + App.getExrServerPool().getServerCount()
                        + " servers: "
                        + App.getExrEndpoint());
        spawnExrCapabilityProbe();
    }

    private int consumeConfigSourceValue(int index) {
        // Flag already handled in App.initCoinConfigs(); consume the value here to avoid
        // falling through to the interactive menu.
        if (index + 1 < arguments.length && !arguments[index + 1].startsWith("--")) {
            return index + 1;
        }
        String envVal = App.getEnv("BLOCKCHAIN_CONFIGURATION_FILES");
        if (envVal == null || envVal.isEmpty()) {
            LOGGER.warning("Missing value after '--blockchain-configuration-files'");
        }
        return index;
    }

    private void createDefaultWallet(Scanner input, int index) {
        if (KeyHandler.existsBaseECKeyFromLocal()) {
            LOGGER.info("Wallet already exists");
            System.exit(0);
        }
        char[] password = readPasswordChars(input, index + 1, "", "WALLET_PASSWORD");
        try {
            if (KeyHandler.calculatePasswordStrength(password) < 9) {
                logBadPassword(null);
                System.exit(1);
            }
            if (KeyHandler.getBaseSeed(password) == null) {
                logBadPassword(null);
                System.exit(1);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void createWalletFromMnemonic(Scanner input, int index) {
        if (KeyHandler.existsBaseECKeyFromLocal()) {
            LOGGER.info("Wallet already exists");
            System.exit(0);
        }
        char[] password = readPasswordChars(input, index + 1, "", "WALLET_PASSWORD");
        char[] mnemonicChars =
                readPasswordChars(input, index + 2, "Mnemonic:\n", "WALLET_MNEMONIC");
        try {
            String mnemonic = new String(mnemonicChars).trim();
            if (KeyHandler.calculatePasswordStrength(password) < 9) {
                logBadPassword(null);
                System.exit(1);
            }
            if (mnemonic.isEmpty()) {
                logBadMnemonic();
                System.exit(1);
            }
            if (!KeyHandler.importFromMnemonic(Arrays.asList(mnemonic.split(" ")), password)) {
                logBadMnemonic();
                System.exit(1);
            }
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(mnemonicChars, '\0');
        }
    }

    private void loginWithPassword(Scanner input, int index) {
        char[] password = readPasswordChars(input, index + 1, "", "WALLET_PASSWORD");
        try {
            if (!KeyHandler.existsBaseECKeyFromLocal()
                    && KeyHandler.calculatePasswordStrength(password) < 9) {
                LOGGER.info("Bad password.");
                System.exit(1);
            }
            completeLogin(password, null, false);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void printMnemonic(Scanner input, int index) {
        char[] password = readPasswordChars(input, index + 1, "", "WALLET_PASSWORD");
        try {
            if (!KeyHandler.existsBaseECKeyFromLocal()) {
                LOGGER.info("No wallet found.");
                System.exit(1);
            }
            String mnemonic = CoinInstance.getMnemonicForPw(password);
            if (mnemonic == null || mnemonic.isEmpty()) {
                // Wrong password (or unusable wallet) must not masquerade as success with
                // an empty phrase. Wipe BEFORE exiting — System.exit does not unwind, so
                // the finally block would be skipped.
                LOGGER.severe(
                        "Error(BADPASSWORD): could not retrieve mnemonic for the supplied"
                                + " password");
                Arrays.fill(password, '\0');
                System.exit(4);
            }
            System.out.println(mnemonic);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void changePassword(Scanner input, int index) {
        if (!KeyHandler.existsBaseECKeyFromLocal()) {
            logBadChangePass("Wallet not found");
            System.exit(1);
        }
        char[] currentPassword = readPasswordChars(input, index + 1, "", "WALLET_PASSWORD");
        char[] newPassword = readPasswordChars(input, index + 2, "", null);
        try {
            applyPasswordChange(currentPassword, newPassword);
        } finally {
            Arrays.fill(currentPassword, '\0');
            Arrays.fill(newPassword, '\0');
        }
    }

    private void applyPasswordChange(char[] currentPassword, char[] newPassword) {
        if (currentPassword.length == 0 || newPassword.length == 0) {
            LOGGER.info("Password cannot be empty");
            System.exit(1);
        }
        if (Arrays.equals(currentPassword, newPassword)) {
            LOGGER.info("New password must be different from old password");
            System.exit(1);
        }
        if (KeyHandler.calculatePasswordStrength(newPassword) < 9) {
            LOGGER.info("Unable to change the password: New password is not strong enough");
            System.exit(1);
        }
        CoinInstance.CoinError error = CoinInstance.changePassword(currentPassword, newPassword);
        if (error != null) {
            logBadChangePass(error.getMessage());
        } else {
            LOGGER.info("Wallet password changed successfully");
        }
    }

    /** Logs in from the environment, so a headless container needs no menu. */
    private boolean loginFromEnvironment() {
        String mnemonic = App.getEnv("WALLET_MNEMONIC");
        if (mnemonic != null && !mnemonic.isEmpty()) {
            loginWithMnemonic(mnemonic);
            return true;
        }
        String password = App.getEnv("WALLET_PASSWORD");
        if (password != null && !password.isEmpty()) {
            loginWithPassword(password);
            return true;
        }
        return false;
    }

    private void loginWithMnemonic(String mnemonic) {
        char[] mnemonicChars = mnemonic.toCharArray();
        try {
            completeLogin(mnemonicChars, null, true);
        } finally {
            Arrays.fill(mnemonicChars, '\0');
        }
    }

    private void loginWithPassword(String password) {
        char[] passwordChars = password.toCharArray();
        try {
            if (!KeyHandler.existsBaseECKeyFromLocal()
                    && KeyHandler.calculatePasswordStrength(passwordChars) < 9) {
                LOGGER.info("Bad password.");
                return;
            }
            completeLogin(passwordChars, null, false);
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
    }

    private void interactiveMenu(Scanner input, String newWalletStr) {
        while (true) {
            if (runMenuSelection(input, readSelection(input, newWalletStr))) {
                return;
            }
        }
    }

    private static int readSelection(Scanner input, String newWalletStr) {
        LOGGER.info("-------------------------");
        LOGGER.info("1 - Create new wallet " + newWalletStr);
        LOGGER.info("2 - Decrypt wallet");
        LOGGER.info("3 - Import from mnemonic");
        LOGGER.info("4 - Quit");
        LOGGER.info("Selection: ");
        int selection = input.nextInt();
        input.nextLine();
        return selection;
    }

    /** Runs one menu selection; returns true when the menu is done. */
    private boolean runMenuSelection(Scanner input, int selection) {
        return switch (selection) {
            case 1 -> {
                createWallet(input);
                yield true;
            }
            case 2 -> {
                unlockWallet(input);
                yield true;
            }
            case 3 -> {
                importMnemonic(input);
                yield true;
            }
            case 4 -> {
                LOGGER.info("Exiting...");
                System.exit(0);
                yield true;
            }
            default -> {
                LOGGER.info("Unknown Option.");
                yield false;
            }
        };
    }

    private void createWallet(Scanner input) {
        if (KeyHandler.existsBaseECKeyFromLocal()) {
            LOGGER.info("Key already exists");
            return;
        }
        Console console = System.console();
        char[] password;
        if (console != null) {
            password = console.readPassword("Enter new password: ");
        } else {
            LOGGER.info("Enter new password: ");
            password = input.next().toCharArray();
        }
        try {
            if (KeyHandler.calculatePasswordStrength(password) < 9) {
                LOGGER.info("Bad password.");
                return;
            }
            completeLogin(password, null, false);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void unlockWallet(Scanner input) {
        LOGGER.info("Enter password: ");
        Console console = System.console();
        char[] password;
        if (console != null) {
            password = console.readPassword();
        } else {
            LOGGER.warning("Console not available, using Scanner fallback");
            password = readPasswordChars(input, NO_ARGUMENT, "", null);
        }
        try {
            if (!KeyHandler.existsBaseECKeyFromLocal()
                    && KeyHandler.calculatePasswordStrength(password) < 9) {
                LOGGER.info("Bad password.");
                return;
            }
            completeLogin(password, null, false);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void importMnemonic(Scanner input) {
        LOGGER.info("Enter mnemonic: ");
        String mnemonic = input.nextLine().trim();
        char[] mnemonicChars = mnemonic.toCharArray();
        try {
            completeLogin(mnemonicChars, null, true);
        } finally {
            Arrays.fill(mnemonicChars, '\0');
        }
    }

    public void deinit() {
        if (backgroundTimerThread != null) {
            backgroundTimerThread.stop();
        }
        for (CoinTicker cointicker : CoinTicker.coins()) {
            CoinInstance instance = CoinInstance.getInstance(cointicker);
            if (instance != null) {
                instance.deinit();
            }
        }
    }

    private void completeLogin(char[] password, String userMnemonic, boolean isMnemonic) {
        if (password == null && userMnemonic == null) {
            logBadPassword(null);
            System.exit(5);
        }
        long startTime = System.currentTimeMillis();
        ensureCoinConfigs();
        initMasterWallet(password, isMnemonic);
        initializeCoinsConcurrently(otherCoins(), password, null, isMnemonic);
        LOGGER.info(
                "[coin] Concurrent coins initialization completed in "
                        + (System.currentTimeMillis() - startTime)
                        + " ms");
        startBackgroundServices();
    }

    /**
     * Self-healing for newly listed coins on stale datadirs.
     *
     * <p>A missing config is created with RPC enabled and a freshly generated random password,
     * which is not what the daemon's own first run does (it writes {@code rpcEnabled=false}), and
     * it is what turns such a coin from "not initialised, no listener" into "initialised, port
     * bound" on the next login. Existing configs are never touched, so no configured coin's
     * credentials or port change.
     */
    private static void ensureCoinConfigs() {
        Map<CoinTicker, CoinConfigEnsurer.Outcome> report =
                CoinConfigEnsurer.ensureMissing(CoinTicker.coins());
        long repaired =
                report.values().stream()
                        .filter(outcome ->
                                outcome == CoinConfigEnsurer.Outcome.CREATED
                                        || outcome == CoinConfigEnsurer.Outcome.CORRUPT_QUARANTINED)
                        .count();
        if (repaired > 0) {
            LOGGER.info(
                    "[coin] Coin config self-healing repaired " + repaired + " coin(s): " + report);
        }
    }

    /**
     * Initialises the master wallet. An authentication failure exits nonzero: scripts and the GUI
     * treat exit code 0 as success, so the credential is wiped before exiting (System.exit does not
     * unwind caller finally blocks).
     */
    private static void initMasterWallet(char[] password, boolean isMnemonic) {
        CoinInstance.CoinError coinError =
                CoinInstance.getInstance(CoinTicker.BLOCKNET).init(password, null, isMnemonic);
        if (coinError == null) {
            return;
        }
        LOGGER.severe(
                "[master] Error(" + coinError.getCode().name() + "): " + coinError.getMessage());
        if (password != null) {
            Arrays.fill(password, '\0');
        }
        System.exit(5);
    }

    private static List<CoinTicker> otherCoins() {
        List<CoinTicker> otherCoins = new ArrayList<>();
        for (CoinTicker coinTicker : CoinTicker.coins()) {
            if (coinTicker != CoinTicker.BLOCKNET) {
                otherCoins.add(coinTicker);
            }
        }
        return otherCoins;
    }

    private void startBackgroundServices() {
        App.getMasterRpc().start();
        backgroundTimerThread = new BackgroundTimerThread();
        // Sole deliberate non-daemon anchor: this named thread is what keeps the JVM alive
        // while the wallet runs. Every long-lived pool in the process (RPC, servers, probes,
        // timer scheduler) is daemonized; the coin-init pool is short-lived, daemon-factory,
        // and always drained in its finally. deinit()/stop() releases this anchor on
        // shutdown.
        Thread timerAnchor = new Thread(backgroundTimerThread, "background-timer-anchor");
        timerAnchor.start();
        if (App.getExrServerPool() != null) {
            App.getExrServerPool().probeAllCapabilities();
        }
    }

    private void initializeCoinsConcurrently(
            List<CoinTicker> coinTickers,
            char[] password,
            String userMnemonic,
            boolean isMnemonic) {
        if (coinTickers.isEmpty()) {
            return;
        }

        int threadCount = Math.min(coinTickers.size(), 8);
        // Daemon factory: init tasks are bounded by their futures and the
        // pool is drained in finally — it must never become a second
        // liveness anchor if a task survives shutdownNow's interrupt.
        ExecutorService executor =
                Executors.newFixedThreadPool(
                        threadCount,
                        r -> {
                            Thread t = new Thread(r, "coin-init");
                            t.setDaemon(true);
                            return t;
                        });

        try {
            List<CoinTicker> enabledCoins =
                    coinTickers.stream()
                            .filter(ticker ->
                                    ticker == CoinTicker.BLOCKNET
                                            || CoinInstance.getInstance(ticker) != null)
                            .collect(Collectors.toList());

            CompletableFuture<?>[] futures =
                    enabledCoins.stream()
                            .map(coinTicker ->
                                    CompletableFuture.runAsync(
                                            () -> {
                                                try {
                                                    LOGGER.fine(
                                                            "[coin] Initializing "
                                                                    + CoinTickerUtils
                                                                            .tickerToString(
                                                                                    coinTicker)
                                                                    + " concurrently");
                                                    CoinInstance.CoinError coinError =
                                                            CoinInstance.getInstance(coinTicker)
                                                                    .init(
                                                                            password,
                                                                            userMnemonic,
                                                                            isMnemonic);
                                                    if (coinError != null) {
                                                        LOGGER.warning(
                                                                "["
                                                                        + coinTicker.name()
                                                                        + "] Error("
                                                                        + coinError.getCode().name()
                                                                        + "): "
                                                                        + coinError.getMessage());
                                                    }
                                                } catch (Exception e) {
                                                    LOGGER.severe(
                                                            "Failed to initialize "
                                                                    + coinTicker.name()
                                                                    + ", "
                                                                    + e.getMessage());
                                                }
                                            },
                                            executor))
                            .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(futures).join();

        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Deferred best-effort capability probe for a freshly configured EXR pool. Daemon thread: a
     * hung endpoint must never hold the JVM.
     */
    private void spawnExrCapabilityProbe() {
        Thread prober =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(1000);
                                App.getExrServerPool().probeAllCapabilities();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        "EXR-Capability-Prober");
        prober.setDaemon(true);
        prober.start();
    }

    private void autoGenerateRPCConfig() {
        for (CoinTicker cointicker : CoinTicker.coins()) {
            ConfigHelper configHelper =
                    new ConfigHelper(CoinTickerUtils.tickerToString(cointicker));

            configHelper.setRpcUsername(SecureTokens.urlSafe(24));
            configHelper.setRpcPassword(SecureTokens.urlSafe(32));
            configHelper.setRpcEnabled(true);
            configHelper.writeConfig();
        }
        ConfigHelper masterConf = new ConfigHelper(ConfigHelper.MASTER_TICKER);
        masterConf.setRpcUsername(SecureTokens.urlSafe(24));
        masterConf.setRpcPassword(SecureTokens.urlSafe(32));
        masterConf.setRpcEnabled(true);
        masterConf.writeConfig();
    }

    /**
     * Reads a secret as char[] from the argument list, an environment variable, or stdin. A
     * negative {@code argPos} means this is not an argument-driven call. Caller MUST zero-fill the
     * returned array after use.
     */
    private char[] readPasswordChars(Scanner input, int argPos, String msg, String envVar) {
        String prompt = msg.isEmpty() ? "Password:\n" : msg;
        if (argPos < 0 || arguments.length <= argPos || arguments[argPos].contains("--")) {
            if (envVar != null) {
                String envVal = App.getEnv(envVar);
                if (envVal != null && !envVal.isEmpty()) {
                    return envVal.toCharArray();
                }
            }
            System.out.println(prompt);
            return input.nextLine().toCharArray();
        }
        return arguments[argPos].toCharArray();
    }

    private static void displayHelp() {
        System.out.print(getHelpText());
    }

    public static String getHelpText() {
        return "Usage: xlite-daemon [options]\n"
                + "Options:\n"
                + "  --enablerpcandconfigure    Enable and configure RPC\n"
                + "  --development-endpoint     Set a custom development endpoint\n"
                + "                             Example: --development-endpoint"
                + " <https://url.endpoint.org/>\n"
                + "  --exr-endpoint             Set EXR endpoint for EXR server\n"
                + "                             Example: --exr-endpoint"
                + " <http://exrproxy1.airdns.org:42114>\n"
                + "  --blockchain-configuration-files  Set blockchain-configuration-files"
                + " source\n"
                + "                             Example: --blockchain-configuration-files"
                + " <path-or-url>\n"
                + "  --version                  Display the version\n"
                + "  --createdefaultwallet     Create a default wallet\n"
                + "  --createwalletmnemonic    Create a wallet with a mnemonic\n"
                + "  --password                Set password without prompt\n"
                + "                           Example: --password <your_password>\n"
                + "  --getmnemonic             Retrieve mnemonic for a password\n"
                + "                           Example: --getmnemonic <your_password>\n"
                + "  --changepassword          Change wallet password\n"
                + "                           Example: --changepassword <current_password>"
                + " <new_password>\n";
    }
}
