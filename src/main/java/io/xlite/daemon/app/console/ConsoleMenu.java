package io.xlite.daemon.app.console;

import io.xlite.daemon.app.App;
import io.xlite.daemon.app.Version;
import io.xlite.daemon.app.crypto.KeyHandler;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** Upper bound on waiting for coin initialisation before the master endpoint starts. */
    private static final int COIN_INIT_TIMEOUT_SECONDS = 30;

    private final String[] arguments;
    private List<String> activeArguments = List.of();
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

        // Environment variables are already applied; refuse a mixed pair before any
        // wallet work. Startup flags are applied first, so their position relative
        // to --password does not matter.
        validateProviderOrExit();
        List<String> walletArguments = applyStartupFlags();
        if (!walletArguments.isEmpty() && handleArguments(walletArguments, input)) {
            return;
        }
        if (loginFromEnvironment()) {
            return;
        }
        interactiveMenu(input, newWalletStr);
    }

    /** Refuses a mixed provider configuration, ending the process instead of starting one. */
    private static void validateProviderOrExit() {
        try {
            App.validateProviderConfig();
        } catch (IllegalStateException error) {
            LOGGER.severe(error.getMessage());
            System.exit(1);
        }
    }

    /**
     * Applies startup flags, returning the remaining wallet-action arguments.
     *
     * <p>Package-private so the ordering contract stays pinned by a unit test: every startup flag
     * is consumed before any wallet action runs, regardless of where the flag appears in the
     * command line.
     */
    List<String> applyStartupFlags() {
        List<String> walletArguments = new ArrayList<>();
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            switch (argument) {
                case "--enablerpcandconfigure" -> {
                    autoGenerateRPCConfig();
                    System.exit(0);
                }
                case "--development-endpoint" -> index = setDevelopmentEndpoint(arguments, index);
                case "--exr-endpoint" -> index = setExrEndpoint(arguments, index);
                case "--electrum-endpoint" -> index = setElectrumEndpoint(arguments, index);
                case "--blockchain-configuration-files" ->
                        index = consumeConfigSourceValue(arguments, index);
                case "--version" -> {
                    LOGGER.info(Version.CLIENT_VERSION);
                    System.exit(0);
                }
                case "--help" -> {
                    displayHelp();
                    System.exit(0);
                }
                default -> walletArguments.add(argument);
            }
        }
        return walletArguments;
    }

    /** Handles wallet actions. Returns true when startup is finished and no menu is due. */
    private boolean handleArguments(List<String> args, Scanner input) {
        activeArguments = args;
        for (int index = 0; index < args.size(); index++) {
            index = handleWalletAction(args, input, index);
            if (index == END_OF_ARGUMENTS) {
                return true;
            }
        }
        return false;
    }

    private int handleWalletAction(List<String> args, Scanner input, int index) {
        String argument = args.get(index);
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

    private int setDevelopmentEndpoint(String[] args, int index) {
        if (index + 1 < args.length) {
            String customEndpoint = args[index + 1];
            if (customEndpoint.startsWith("--")) {
                LOGGER.warning("Invalid endpoint: " + customEndpoint);
                return index;
            }
            App.setBaseUrl(customEndpoint);
            validateProviderOrExit();
            return index + 1;
        }
        String envEndpoint = App.getEnv("BASE_URL");
        if (envEndpoint != null && !envEndpoint.isEmpty()) {
            App.setBaseUrl(envEndpoint);
            validateProviderOrExit();
        } else {
            LOGGER.warning("Missing custom endpoint after '--development-endpoint'");
        }
        return index;
    }

    private int setExrEndpoint(String[] args, int index) {
        if (index + 1 < args.length) {
            String exrEndpoint = args[index + 1];
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

    private int setElectrumEndpoint(String[] args, int index) {
        if (index + 1 < args.length) {
            String endpoint = args[index + 1];
            if (endpoint.startsWith("--")) {
                LOGGER.warning("Invalid endpoint: " + endpoint);
                return index;
            }
            App.setElectrumEndpoint(endpoint);
            LOGGER.info("[console] native history enabled through " + endpoint);
            return index + 1;
        }
        String envEndpoint = App.getEnv("ELECTRUM_ENDPOINT");
        if (envEndpoint != null && !envEndpoint.isEmpty()) {
            App.setElectrumEndpoint(envEndpoint);
            LOGGER.info("[console] native history enabled through " + envEndpoint);
        } else {
            LOGGER.warning("Missing endpoint after '--electrum-endpoint'");
        }
        return index;
    }

    private void enableExr(String endpoint) {
        App.setExrEndpoint(endpoint);
        LOGGER.info("[console] EXR provider enabled: " + endpoint);
        validateProviderOrExit();
    }

    private int consumeConfigSourceValue(String[] args, int index) {
        // Flag already handled in App.initCoinConfigs(); consume the value here to avoid
        // falling through to the interactive menu.
        if (index + 1 < args.length && !args[index + 1].startsWith("--")) {
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
            LOGGER.info(
                    "Wallet created. Run --getmnemonic <password> to display the recovery phrase.");
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
            System.exit(1);
        }
        LOGGER.info("Wallet password changed successfully");
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
        char[] password = readConfirmedPassword(input);
        if (password == null) {
            return;
        }
        try {
            if (KeyHandler.calculatePasswordStrength(password) < 9) {
                LOGGER.info("Bad password.");
                return;
            }
            completeLogin(password, null, false);
            showRecoveryPhrase(password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private void showRecoveryPhrase(char[] password) {
        if (System.console() == null) {
            LOGGER.info(
                    "Wallet created. Run --getmnemonic <password> to display the recovery phrase.");
            return;
        }
        List<String> seed = KeyHandler.getBaseSeed(password);
        if (seed == null) {
            LOGGER.warning("Wallet created, but the recovery phrase could not be read.");
            return;
        }
        System.out.println(String.join(" ", seed));
        System.out.println(
                "Store this recovery phrase offline. It is the only way to restore the wallet"
                        + " without the password.");
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
        for (CoinInstance instance : CoinInstance.getCoinInstances()) {
            instance.deinit();
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

            awaitCoins(futures);

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
     * Waits for coin initialisation without letting one wedged coin hold the master endpoint — the
     * GUI's readiness signal — closed. Coins run on daemon threads, so a straggler keeps finishing
     * in the background and the master listener starts regardless.
     */
    private static void awaitCoins(CompletableFuture<?>[] futures) {
        if (futures.length == 0) {
            return;
        }
        try {
            CompletableFuture.allOf(futures).get(COIN_INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOGGER.warning(
                    "[coin] Coin initialization still running after "
                            + COIN_INIT_TIMEOUT_SECONDS
                            + "s; starting the master endpoint anyway");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOGGER.warning("[coin] Coin initialization failed: " + e.getMessage());
        }
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
    private char[] readConfirmedPassword(Scanner input) {
        char[] first = readPasswordInteractive(input, "Enter new password: ");
        char[] second = readPasswordInteractive(input, "Confirm password: ");
        boolean matches = Arrays.equals(first, second);
        Arrays.fill(second, '\0');
        if (!matches) {
            Arrays.fill(first, '\0');
            LOGGER.info("Passwords do not match.");
            return null;
        }
        return first;
    }

    private char[] readPasswordInteractive(Scanner input, String prompt) {
        Console console = System.console();
        if (console != null) {
            return console.readPassword(prompt);
        }
        LOGGER.info(prompt);
        return input.next().toCharArray();
    }

    private char[] readPasswordChars(Scanner input, int argPos, String msg, String envVar) {
        String prompt = msg.isEmpty() ? "Password:\n" : msg;
        if (argPos < 0
                || activeArguments.size() <= argPos
                || activeArguments.get(argPos).contains("--")) {
            if (envVar != null) {
                String envVal = App.getEnv(envVar);
                if (envVal != null && !envVal.isEmpty()) {
                    return envVal.toCharArray();
                }
            }
            // Prompts are UI, not payload: stdout must carry only the requested value so
            // --getmnemonic can be parsed without filtering a prompt line out of the phrase.
            System.err.println(prompt);
            return input.nextLine().toCharArray();
        }
        return activeArguments.get(argPos).toCharArray();
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
                + "  --electrum-endpoint        Base URL of the native ElectrumX hub\n"
                + "                             Example: --electrum-endpoint ws://127.0.0.1:5000\n"
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
