package io.cloudchains.app.console;

import io.cloudchains.app.App;
import io.cloudchains.app.Version;
import io.cloudchains.app.crypto.KeyHandler;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.api.http.client.EXRServerPool;
import io.cloudchains.app.util.ConfigHelper;
import io.cloudchains.app.util.background.BackgroundTimerThread;

import java.io.Console;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
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
        LOGGER.info("[master] Error(" + CoinInstance.CoinError.CoinErrorCode.BADPASSWORD.name() + "): " + msg);
    }

    public void logBadMnemonic() {
        LOGGER.info("[master] Error(" + CoinInstance.CoinError.CoinErrorCode.BADMNEMONIC.name() + "): Bad mnemonic");
    }

    public void logBadChangePass(String msg) {
        LOGGER.info("[master] Error(" + CoinInstance.CoinError.CoinErrorCode.CHANGEPASSWORDFAILED.name() + "): " + msg);
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
                        if (i + 1 < arguments.length) {
                            String customEndpoint = arguments[i + 1];
                            if (customEndpoint.startsWith("--")) {
                                LOGGER.warning("Invalid endpoint: " + customEndpoint);
                                break;
                            }
                            App.BASE_URL = customEndpoint;
                            i++;
                        } else {
                            String envEndpoint = App.getEnv("BASE_URL");
                            if (envEndpoint != null && !envEndpoint.isEmpty()) {
                                App.BASE_URL = envEndpoint;
                            } else {
                                LOGGER.warning("Missing custom endpoint after '--development-endpoint'");
                            }
                        }
                        break;
                    }
                    case "--exr-endpoint": {
                        if (i + 1 < arguments.length) {
                            String exrEndpoint = arguments[i + 1];
                            if (exrEndpoint.startsWith("--")) {
                                LOGGER.warning("Invalid endpoint: " + exrEndpoint);
                                break;
                            }
                            App.EXR_ENDPOINT = exrEndpoint;
                            App.exrServerPool = new EXRServerPool(App.EXR_ENDPOINT);
                            LOGGER.info("[console] EXR mode enabled with " + App.exrServerPool.getServerCount() + " servers: " + App.EXR_ENDPOINT);
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
                                LOGGER.info("[console] EXR mode enabled with " + App.exrServerPool.getServerCount() + " servers: " + App.EXR_ENDPOINT);
                                new Thread(() -> {
                                    try {
                                        Thread.sleep(1000);
                                        App.exrServerPool.probeAllCapabilities();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }, "EXR-Capability-Prober").start();
                            } else {
                                LOGGER.warning("Missing EXR endpoint after '--exr-endpoint'");
                            }
                        }
                        break;
                    }
                    case "--version":
                        LOGGER.info(Version.CLIENT_VERSION);
                        System.exit(0);
                        break;
                    case "--createdefaultwallet": {
                        if (KeyHandler.existsBaseECKeyFromLocal()) {
                            LOGGER.info("Wallet already exists");
                            System.exit(0);
                        }

                        char[] password = readPasswordChars(input, arguments, i + 1, "", "WALLET_PASSWORD");
                        try {
                            int strength = KeyHandler.calculatePasswordStrength(password);
                            if (strength < 9) {
                                logBadPassword(null);
                                System.exit(1);
                            }

                            List<String> mnemonic = KeyHandler.getBaseSeed(password);
                            if (mnemonic == null) {
                                logBadPassword(null);
                                System.exit(1);
                            }
                        } finally {
                            Arrays.fill(password, '\0');
                        }

                        System.exit(0);
                    }
                    case "--createwalletmnemonic": {
                        if (KeyHandler.existsBaseECKeyFromLocal()) {
                            LOGGER.info("Wallet already exists");
                            System.exit(0);
                        }

                        char[] password = readPasswordChars(input, arguments, i + 1, "", "WALLET_PASSWORD");
                        String mnemonic = readPassword(input, arguments, i + 2, "Mnemonic:\n", "WALLET_MNEMONIC").trim();
                        try {
                            int strength = KeyHandler.calculatePasswordStrength(password);
                            if (strength < 9) {
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
                        }

                        System.exit(0);
                    }
                    case "--xliterpc": {
                        xliteRPC = true;
                        break;
                    }
                    case "--password": {
                        char[] password = readPasswordChars(input, arguments, i + 1, "", "WALLET_PASSWORD");
                        try {
                            int strength = KeyHandler.calculatePasswordStrength(password);

                            if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                                LOGGER.info("Bad password.");
                                System.exit(1);
                            }

                            completeLogin(password, null, false);
                        } finally {
                            Arrays.fill(password, '\0');
                        }

                        return;
                    }
                    case "--getmnemonic": {
                        char[] password = readPasswordChars(input, arguments, i + 1, "", "WALLET_PASSWORD");
                        try {
                            if (!KeyHandler.existsBaseECKeyFromLocal()) {
                                LOGGER.info("No wallet found.");
                                System.exit(1);
                            }

                            String mnemonic = CoinInstance.getMnemonicForPw(password);
                            System.out.println(mnemonic);
                        } finally {
                            Arrays.fill(password, '\0');
                        }
                        System.exit(0);
                    }
                    case "--changepassword": {
                        if (!KeyHandler.existsBaseECKeyFromLocal()) {
                            logBadChangePass("Wallet not found");
                            System.exit(1);
                        }

                        char[] currentPassword = readPasswordChars(input, arguments, i + 1, "", "WALLET_PASSWORD");
                        char[] newPassword = readPasswordChars(input, arguments, i + 2, "", null);
                        try {
                            if (currentPassword.length == 0 || newPassword.length == 0) {
                                LOGGER.info("Password cannot be empty");
                                System.exit(1);
                            }
                            if (Arrays.equals(currentPassword, newPassword)) {
                                LOGGER.info("New password must be different from old password");
                                System.exit(1);
                            }

                            int strength = KeyHandler.calculatePasswordStrength(newPassword);
                            if (strength < 9) {
                                LOGGER.info("Unable to change the password: New password is not strong enough");
                                System.exit(1);
                            }

                            CoinInstance.CoinError err = CoinInstance.changePassword(currentPassword, newPassword);
                            if (err != null)
                                logBadChangePass(err.getMessage());
                            else
                                LOGGER.info("Wallet password changed successfully");
                        } finally {
                            Arrays.fill(currentPassword, '\0');
                            Arrays.fill(newPassword, '\0');
                        }

                        System.exit(0);
                    }
                    case "--help":
                        displayHelp();
                        System.exit(0);
                }
            }
        }

        String mnemonicImport = App.getEnv("WALLET_MNEMONIC");
        if (mnemonicImport != null && !mnemonicImport.isEmpty()) {
            char[] mnemonicChars = mnemonicImport.toCharArray();
            try {
                completeLogin(mnemonicChars, null, true);
            } finally {
                Arrays.fill(mnemonicChars, '\0');
            }
            return;
        } else {
            String passwordEnv = App.getEnv("WALLET_PASSWORD");
            if (passwordEnv != null && !passwordEnv.isEmpty()) {
                char[] password = passwordEnv.toCharArray();
                try {
                    int strength = KeyHandler.calculatePasswordStrength(password);

                    if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                        LOGGER.info("Bad password.");
                        return;
                    }

                    completeLogin(password, null, false);
                } finally {
                    Arrays.fill(password, '\0');
                }
                return;
            }
        }

        while (true) {
            LOGGER.info("-------------------------");
            LOGGER.info("1 - Create new wallet " + newWalletStr);
            LOGGER.info("2 - Decrypt wallet");
            LOGGER.info("3 - Import from mnemonic");
            LOGGER.info("4 - Quit");

            LOGGER.info("Selection: ");
            selection = input.nextInt();
            input.nextLine();

            switch (selection) {
                case 1: {
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
                        int strength = KeyHandler.calculatePasswordStrength(password);

                        if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                            LOGGER.info("Bad password.");
                            return;
                        }
                        completeLogin(password, null, false);
                    } finally {
                        Arrays.fill(password, '\0');
                    }
                    return;
                }
                case 2: {
                    LOGGER.info("Enter password: ");
                    Console console = System.console();
                    char[] password;
                    if (console != null) {
                        password = console.readPassword();
                    } else {
                        LOGGER.warning("Console not available, using Scanner fallback");
                        password = readPasswordChars(input, null, 0, "", null);
                    }
                    try {
                        int strength = KeyHandler.calculatePasswordStrength(password);

                        if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                            LOGGER.info("Bad password.");
                            return;
                        }
                        completeLogin(password, null, false);
                    } finally {
                        Arrays.fill(password, '\0');
                    }
                    return;
                }
                case 3: {
                    LOGGER.info("Enter mnemonic: ");
                    String mnemonicInput = input.nextLine().trim();

                    char[] mnemonicChars = mnemonicInput.toCharArray();
                    try {
                        completeLogin(mnemonicChars, null, true);
                    } finally {
                        Arrays.fill(mnemonicChars, '\0');
                    }
                    return;
                }
                case 4: {
                    LOGGER.info("Exiting...");
                    System.exit(0);
                }
                default: {
                    LOGGER.info("Unknown Option.");
                }
            }
        }
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

    private void completeLogin(char[] password, String userMnemonic, boolean isMnemonic) {
        if (password == null && userMnemonic == null) {
            logBadPassword(null);
            System.exit(0);
        }

        long startTime = System.currentTimeMillis();

        CoinInstance.CoinError coinError = CoinInstance.getInstance(CoinTicker.BLOCKNET).init(password, null, isMnemonic, xliteRPC);
        if (coinError != null) {
            String msg = "[master] Error(" + coinError.getCode().name() + "): " + coinError.getMessage();
            LOGGER.severe(msg);
            System.exit(0);
        }

        List<CoinTicker> otherCoins = new ArrayList<>();
        for (CoinTicker cointicker : CoinTicker.coins()) {
            if (cointicker != CoinTicker.BLOCKNET && cointicker != CoinTicker.BLOCKNET_TESTNET5) {
                otherCoins.add(cointicker);
            }
        }

        initializeCoinsConcurrently(otherCoins, password, null, isMnemonic, xliteRPC);

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        LOGGER.info("[coin] Concurrent coins initialization completed in " + totalTime + " ms");

        App.masterRPC.start();
        backgroundTimerThread = new BackgroundTimerThread();
        (new Thread(backgroundTimerThread)).start();
        if (App.exrServerPool != null) {
            App.exrServerPool.probeAllCapabilities();
        }
    }

    private void initializeCoinsConcurrently(List<CoinTicker> coinTickers, char[] password,
                                             String userMnemonic, boolean isMnemonic, boolean xliteRPC) {
        if (coinTickers.isEmpty()) {
            return;
        }

        int threadCount = Math.min(coinTickers.size(), 8);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            List<CoinTicker> enabledCoins = coinTickers.stream()
                    .filter(ticker -> ticker == CoinTicker.BLOCKNET || CoinInstance.getInstance(ticker) != null)
                    .collect(Collectors.toList());

            CompletableFuture<?>[] futures = enabledCoins.stream()
                    .map(coinTicker -> CompletableFuture.runAsync(() -> {
                        try {
                            LOGGER.fine("[coin] Initializing " + CoinTickerUtils.tickerToString(coinTicker) + " concurrently");
                            CoinInstance.CoinError coinError = CoinInstance.getInstance(coinTicker)
                                    .init(password, userMnemonic, isMnemonic, xliteRPC);
                            if (coinError != null) {
                                LOGGER.warning("[" + coinTicker.name() + "] Error(" +
                                        coinError.getCode().name() + "): " + coinError.getMessage());
                            }
                        } catch (Exception e) {
                            LOGGER.severe("Failed to initialize " + coinTicker.name() + ", " + e.getMessage());
                        }
                    }, executor))
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

    private String readPassword(Scanner input, String[] args, int argPos, String msg, String envVar) {
        if (msg.isEmpty())
            msg = "Password:\n";
        if (args == null || args.length <= argPos || args[argPos].contains("--")) {
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

    /**
     * Reads the password as a char[] from args, environment variable, or stdin.
     * Caller MUST zero-fill the returned array after use.
     */
    private char[] readPasswordChars(Scanner input, String[] args, int argPos, String msg, String envVar) {
        if (msg.isEmpty())
            msg = "Password:\n";
        if (args == null || args.length <= argPos || args[argPos].contains("--")) {
            if (envVar != null) {
                String envVal = App.getEnv(envVar);
                if (envVal != null && !envVal.isEmpty())
                    return envVal.toCharArray();
            }
            System.out.println(msg);
            return input.nextLine().toCharArray();
        }
        return args[argPos].toCharArray();
    }

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
