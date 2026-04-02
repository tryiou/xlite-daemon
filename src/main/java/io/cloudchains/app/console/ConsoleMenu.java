package io.cloudchains.app.console;

import io.cloudchains.app.App;
import io.cloudchains.app.Version;
import io.cloudchains.app.crypto.KeyHandler;
import io.cloudchains.app.crypto.LoginUtils;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.api.http.client.EXRServerPool;
import io.cloudchains.app.util.ConfigHelper;
import io.cloudchains.app.util.background.BackgroundTimerThread;

import java.io.Console;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ConsoleMenu {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    private String[] arguments;
    private BackgroundTimerThread backgroundTimerThread = null;
    private boolean xliteRPC = false;

    public ConsoleMenu(String[] args) {
        this.arguments = args;
        LOGGER.setLevel(Level.INFO);
    }

    public void logBadPassword(String msg) {
        if (msg == null || msg.isEmpty())
            msg = "Bad password";
        LOGGER.log(Level.INFO, "[master] Error(" + CoinInstance.CoinError.CoinErrorCode.BADPASSWORD.name() + "): " + msg);
    }

    public void logBadMnemonic() {
        LOGGER.log(Level.INFO, "[master] Error(" + CoinInstance.CoinError.CoinErrorCode.BADMNEMONIC.name() + "): Bad mnemonic");
    }

    public void logBadChangePass(String msg) {
        LOGGER.log(Level.INFO, "[master] Error(" + CoinInstance.CoinError.CoinErrorCode.CHANGEPASSWORDFAILED.name() + "): " + msg);
    }

    public void init() {
        int selection;
        String newWalletStr = "";
        Scanner input = new Scanner(System.in);

        if (KeyHandler.existsBaseECKeyFromLocal()) {
            newWalletStr = "- Disabled. Wallet already exists.";
        }

        if (arguments.length > 0) {
            for (int i = 0; i < arguments.length; i++) {
                String argument = arguments[i];

                switch (argument) {
                    case "--enablerpcandconfigure":
                        autoGenerateRPCConfig();
                        System.exit(0);
                    case "--development-endpoint": {
                        // sample endpoint url: "https://utils.blocknet.org/"
                        if (i + 1 < arguments.length) {
                            String customEndpoint = arguments[i + 1];
                            if (customEndpoint.startsWith("--")) {
                                LOGGER.log(Level.WARNING, "Invalid endpoint: " + customEndpoint);
                                break;
                            }
                            App.BASE_URL = customEndpoint;
                            i++;
                        } else {
                            String envEndpoint = App.getEnv("BASE_URL");
                            if (envEndpoint != null && !envEndpoint.isEmpty()) {
                                App.BASE_URL = envEndpoint;
                            } else {
                                LOGGER.log(Level.WARNING, "Missing custom endpoint after '--development-endpoint'");
                            }
                        }
                        break;
                    }
                    case "--exr-endpoint": {
                        if (i + 1 < arguments.length) {
                            String exrEndpoint = arguments[i + 1];
                            if (exrEndpoint.startsWith("--")) {
                                LOGGER.log(Level.WARNING, "Invalid endpoint: " + exrEndpoint);
                                break;
                            }
                            App.EXR_ENDPOINT = exrEndpoint;
                            App.exrServerPool = new EXRServerPool(App.EXR_ENDPOINT);
                            LOGGER.log(Level.INFO, "[console] EXR mode enabled with " + App.exrServerPool.getServerCount() + " servers: " + App.EXR_ENDPOINT);
                            new Thread(() -> {
                                try {
                                    Thread.sleep(1000);
                                    App.exrServerPool.probeAllCapabilities();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }, "EXR-Capability-Prober").start();
                            i++;
                        } else {
                            String envExrEndpoint = App.getEnv("EXR_ENDPOINT");
                            if (envExrEndpoint != null && !envExrEndpoint.isEmpty()) {
                                App.EXR_ENDPOINT = envExrEndpoint;
                                App.exrServerPool = new EXRServerPool(App.EXR_ENDPOINT);
                                LOGGER.log(Level.INFO, "[console] EXR mode enabled with " + App.exrServerPool.getServerCount() + " servers: " + App.EXR_ENDPOINT);
                                new Thread(() -> {
                                    try {
                                        Thread.sleep(1000);
                                        App.exrServerPool.probeAllCapabilities();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }, "EXR-Capability-Prober").start();
                            } else {
                                LOGGER.log(Level.WARNING, "Missing EXR endpoint after '--exr-endpoint'");
                            }
                        }
                        break;
                    }
                    case "--version":
                        LOGGER.log(Level.INFO, Version.CLIENT_VERSION);
                        System.exit(0);
                        break;
                    case "--createdefaultwallet": {
                        if (KeyHandler.existsBaseECKeyFromLocal()) {
                            LOGGER.log(Level.INFO, "Wallet already exists");
                            System.exit(0);
                        }

                        String password = readPassword(input, arguments, i + 1, "", "WALLET_PASSWORD");
                        int strength = KeyHandler.calculatePasswordStrength(password);
                        if (strength < 9) {
                            logBadPassword(null);
                            System.exit(1);
                        }

                        String entropy = LoginUtils.loginToEntropy(password);
                        completeLogin(entropy, null, false);

                        System.exit(0);
                    }
                    case "--createwalletmnemonic": {
                        if (KeyHandler.existsBaseECKeyFromLocal()) {
                            LOGGER.log(Level.INFO, "Wallet already exists");
                            System.exit(0);
                        }

                        String password = readPassword(input, arguments, i + 1, "", "WALLET_PASSWORD");
                        String mnemonic = readPassword(input, arguments, i + 2, "Mnemonic:\n", "WALLET_MNEMONIC").trim();
                        int strength = KeyHandler.calculatePasswordStrength(password);
                        if (strength < 9) {
                            logBadPassword(null);
                            System.exit(1);
                        }
                        if (mnemonic.isEmpty()) {
                            logBadMnemonic();
                            System.exit(1);
                        }

                        String entropy = LoginUtils.loginToEntropy(password);
                        completeLogin(entropy, mnemonic, false);
                        System.exit(0);
                    }
                    case "--xliterpc": {
                        // Increment RPC port by 1
                        xliteRPC = true;

                        break;
                    }
                    case "--password": {
                        String password = readPassword(input, arguments, i + 1, "", "WALLET_PASSWORD");
                        int strength = KeyHandler.calculatePasswordStrength(password);

                        if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                            LOGGER.log(Level.INFO, "Bad password.");
                            System.exit(1);
                        }

                        String entropy = LoginUtils.loginToEntropy(password);
                        completeLogin(entropy, null, false);

                        return;
                    }
                    case "--getmnemonic": {
                        String password = readPassword(input, arguments, i + 1, "", "WALLET_PASSWORD");

                        if (!KeyHandler.existsBaseECKeyFromLocal()) {
                            LOGGER.log(Level.INFO, "No wallet found.");
                            System.exit(1);
                        }

                        String entropy = LoginUtils.loginToEntropy(password);
                        String mnemonic = CoinInstance.getMnemonicForPw(entropy);
                        System.out.println(mnemonic);
                        System.exit(0);
                    }
                    case "--changepassword": {
                        if (!KeyHandler.existsBaseECKeyFromLocal()) {
                            logBadChangePass("Wallet not found");
                            System.exit(1);
                        }

                        String currentPassword = readPassword(input, arguments, i + 1, "", "WALLET_PASSWORD");
                        String newPassword = readPassword(input, arguments, i + 2, "", null);
                        if (currentPassword.isEmpty() || newPassword.isEmpty()) {
                            LOGGER.log(Level.INFO, "Password cannot be empty");
                            System.exit(1);
                        }
                        if (currentPassword.equals(newPassword)) {
                            LOGGER.log(Level.INFO, "New password must be different from old password");
                            System.exit(1);
                        }

                        // Check new password strength
                        int strength = KeyHandler.calculatePasswordStrength(newPassword);
                        if (strength < 9) {
                            LOGGER.log(Level.INFO, "Unable to change the password: New password is not strong enough");
                            System.exit(1);
                        }

                        CoinInstance.CoinError err = CoinInstance.changePassword(LoginUtils.loginToEntropy(currentPassword),
                                LoginUtils.loginToEntropy(newPassword));
                        if (err != null)
                            logBadChangePass(err.getMessage());
                        else
                            LOGGER.log(Level.INFO, "Wallet password changed successfully");

                        System.exit(0);
                    }
                    case "--help":
                        displayHelp();
                        System.exit(0);
                }
            }
        }

        if (App.getEnv("WALLET_MNEMONIC") != null) {
            String mnemonicImport = App.getEnv("WALLET_MNEMONIC");
            if (mnemonicImport == null) {
                LOGGER.log(Level.INFO, "Bad mnemonic.");
                return;
            }

            completeLogin(mnemonicImport, null, true);
            return;
        } else if (App.getEnv("WALLET_PASSWORD") != null) {
            String password = App.getEnv("WALLET_PASSWORD");
            if (password == null) {
                LOGGER.log(Level.INFO, "Bad password.");
                return;
            }

            int strength = KeyHandler.calculatePasswordStrength(password);

            if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                LOGGER.log(Level.INFO, "Bad password.");
                return;
            }

            completeLogin(LoginUtils.loginToEntropy(password), null, false);
            return;
        }

        String entropy = null;

        while (entropy == null) {
            LOGGER.log(Level.INFO, "-------------------------");
            LOGGER.log(Level.INFO, "1 - Create new wallet " + newWalletStr);
            LOGGER.log(Level.INFO, "2 - Decrypt wallet");
            LOGGER.log(Level.INFO, "3 - Import from mnemonic");
            LOGGER.log(Level.INFO, "4 - Quit");

            LOGGER.log(Level.INFO, "Selection: ");
            selection = input.nextInt();
            input.nextLine(); // clear buffer

            switch (selection) {
                case 1: {
                    if (KeyHandler.existsBaseECKeyFromLocal()) {
                        LOGGER.log(Level.INFO, "Key already exists");
                        return;
                    }

                    Console console = System.console();
                    String password;
                    if (console != null) {
                        password = new String(console.readPassword("Enter new password: "));
                    } else {
                        LOGGER.log(Level.INFO, "Enter new password: ");
                        password = input.next();
                    }
                    int strength = KeyHandler.calculatePasswordStrength(password);

                    if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                        LOGGER.log(Level.INFO, "Bad password.");
                        return;
                    }
                    entropy = LoginUtils.loginToEntropy(password);
                    break;
                }
                case 2: {
                    LOGGER.log(Level.INFO, "Enter password: ");
                    Console console = System.console();
                    String password;
                    if (console != null) {
                        password = new String(console.readPassword());
                    } else {
                        LOGGER.log(Level.WARNING, "Console not available, using Scanner fallback");
                        password = readPassword(input, null, 0, "", null);
                    }
                    int strength = KeyHandler.calculatePasswordStrength(password);

                    if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                        LOGGER.log(Level.INFO, "Bad password.");
                        return;
                    }

                    entropy = LoginUtils.loginToEntropy(password);
                    break;
                }
                case 3: {
                    LOGGER.log(Level.INFO, "Enter mnemonic: ");
                    String mnemonicImport = input.nextLine().trim();

                    completeLogin(mnemonicImport, null, true);
                    return;
                }
                case 4: {
                    LOGGER.log(Level.INFO, "Exiting...");
                    System.exit(0);
                }
                default: {
                    LOGGER.log(Level.INFO, "Unknown Option.");
                }
            }
        }

        input.close();
        completeLogin(entropy, null, false);
    }

    public void deinit() {
        if (backgroundTimerThread != null)
            backgroundTimerThread.stop();
        for (CoinTicker cointicker : CoinTicker.coins()) {
            CoinInstance instance = CoinInstance.getInstance(cointicker);
            if (instance != null)
                instance.deinit();
        }
    }

    private void completeLogin(String entropy, String userMnemonic, boolean isMnemonic) {
        if (entropy == null && userMnemonic == null) {
            logBadPassword(null);
            System.exit(0);
        }

        // Measure total initialization time for all coins
        long startTime = System.currentTimeMillis();
        // Initialize Blocknet first (synchronous) as it's the active currency
        CoinInstance.CoinError coinError = CoinInstance.getInstance(CoinTicker.BLOCKNET).init(entropy, userMnemonic, isMnemonic, xliteRPC);
        if (coinError != null) {
            String msg = "[master] Error(" + coinError.getCode().name() + "): " + coinError.getMessage();
            LOGGER.log(Level.SEVERE, msg);
            System.exit(0);
        }

        // Get all coin tickers except Blocknet (which is already initialized)
        List<CoinTicker> otherCoins = new ArrayList<>();
        for (CoinTicker cointicker : CoinTicker.coins()) {
            if (cointicker != CoinTicker.BLOCKNET && cointicker != CoinTicker.BLOCKNET_TESTNET5) {
                otherCoins.add(cointicker);
            }
        }

        // Initialize remaining coins concurrently
        initializeCoinsConcurrently(otherCoins, entropy, userMnemonic, isMnemonic, xliteRPC);

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        LOGGER.log(Level.INFO, "[coin] Concurrent coins initialization completed in " + totalTime + " ms");

        App.masterRPC.start();
        backgroundTimerThread = new BackgroundTimerThread();
        (new Thread(backgroundTimerThread)).start();
        // Start EXR capability probing after wallet is decrypted
        if (App.exrServerPool != null) {
            App.exrServerPool.probeAllCapabilities();
        }
    }

    /**
     * Initialize coins concurrently using CompletableFuture
     * @param coinTickers List of coin tickers to initialize
     * @param entropy Password entropy
     * @param userMnemonic User mnemonic (if any)
     * @param isMnemonic Whether the input is a mnemonic
     * @param xliteRPC Whether to use xlite RPC
     */
    private void initializeCoinsConcurrently(List<CoinTicker> coinTickers, String entropy,
                                             String userMnemonic, boolean isMnemonic, boolean xliteRPC) {
        if (coinTickers.isEmpty()) {
            return;
        }

        // Create thread pool with number of coins (or a reasonable limit)
        int threadCount = Math.min(coinTickers.size(), 8); // Limit to 8 threads max
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            // Filter to only enabled coins before initialization
            List<CoinTicker> enabledCoins = coinTickers.stream()
                    .filter(ticker -> ticker == CoinTicker.BLOCKNET || CoinInstance.getInstance(ticker) != null)
                    .collect(Collectors.toList());

            // Create CompletableFuture for each coin initialization
            CompletableFuture<?>[] futures = enabledCoins.stream()
                    .map(coinTicker -> CompletableFuture.runAsync(() -> {
                        try {
                            LOGGER.log(Level.FINE, "[coin] Initializing " + CoinTickerUtils.tickerToString(coinTicker) + " concurrently");
                            CoinInstance.CoinError coinError = CoinInstance.getInstance(coinTicker)
                                    .init(entropy, userMnemonic, isMnemonic, xliteRPC);
                            if (coinError != null) {
                                LOGGER.log(Level.WARNING, "[" + coinTicker.name() + "] Error(" +
                                        coinError.getCode().name() + "): " + coinError.getMessage());
                            }
                        } catch (Exception e) {
                            LOGGER.log(Level.SEVERE, "Failed to initialize " + coinTicker.name(), e);
                        }
                    }, executor))
                    .toArray(CompletableFuture[]::new);

            // Wait for all initializations to complete
            CompletableFuture.allOf(futures).join();


        } finally {
            // Shutdown executor service
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

    private void autoGenerateRPCConfig() {
        for (CoinTicker cointicker : CoinTicker.coins()) {
            ConfigHelper configHelper = new ConfigHelper(CoinTickerUtils.tickerToString(cointicker));

            configHelper.setRpcUsername(generateRandomString(24));
            configHelper.setRpcPassword(generateRandomString(32));
            configHelper.setRpcEnabled(true);
            configHelper.writeConfig();
        }
        ConfigHelper masterConf = new ConfigHelper("master");
        masterConf.setRpcUsername(generateRandomString(24));
        masterConf.setRpcPassword(generateRandomString(32));
        masterConf.setRpcEnabled(true);
        masterConf.writeConfig();
    }

    private String generateRandomString(int length) {
        SecureRandom secureRandom = new SecureRandom();

        byte[] token = new byte[length];
        secureRandom.nextBytes(token);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /**
    * Reads the password from args, environment variable, or stdin (in that priority order).
    * When no positional arg is available, checks the env var before falling back to stdin.
    * @param input Stdin
    * @param args Program arguments
    * @param argPos Current arg position
    * @param msg Message to display on stdin (defaults to "Password:\n" if empty)
    * @param envVar Environment variable name to check as fallback (nullable)
    * @return Password string
    */
    private String readPassword(Scanner input, String[] args, int argPos, String msg, String envVar) {
        if (msg.isEmpty())
            msg = "Password:\n";
        if (args.length <= argPos || args[argPos].contains("--")) {
            if (envVar != null) {
                String envVal = App.getEnv(envVar);
                if (envVal != null && !envVal.isEmpty())
                    return envVal;
            }
            System.out.println(msg);
            return input.nextLine();
        }
        return args[argPos];
    }

    // Function to display help information
    private static void displayHelp() {
        System.out.print(getHelpText());
    }

    public static String getHelpText() {
        return "Usage: xlite-daemon [options]\n" +
                "Options:\n" +
                "  --enablerpcandconfigure    Enable and configure RPC\n" +
                "  --development-endpoint     Set a custom development endpoint\n" +
                "                             Example: --development-endpoint <https://url.endpoint.org/>\n" +
                "  --exr-endpoint             Set EXR endpoint for EXR server\n" +
                "                             Example: --exr-endpoint <http://exrproxy1.airdns.org:42114>\n" +
                "  --version                  Display the version\n" +
                "  --createdefaultwallet     Create a default wallet\n" +
                "  --createwalletmnemonic    Create a wallet with a mnemonic\n" +
                "  --xliterpc                Increment RPC port by 1\n" +
                "  --password                Set password without prompt\n" +
                "                           Example: --password <your_password>\n" +
                "  --getmnemonic             Retrieve mnemonic for a password\n" +
                "                           Example: --getmnemonic <your_password>\n" +
                "  --changepassword          Change wallet password\n" +
                "                           Example: --changepassword <current_password> <new_password>\n";
    }
}
