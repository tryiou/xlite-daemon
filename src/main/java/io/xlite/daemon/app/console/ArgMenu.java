package io.xlite.daemon.app.console;

import io.xlite.daemon.app.crypto.KeyHandler;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;

import java.util.Arrays;

public class ArgMenu {
    private final String[] arguments;

    public ArgMenu(String[] args) {
        this.arguments = args == null ? new String[0] : args.clone();
    }

    public void init() {
        System.out.println("-------------------------");
        System.out.println("Help: ");
        System.out.println("Create new wallet: --new-wallet (password)");
        System.out.println("Decrypt wallet: --decrypt-wallet (password)");

        if (arguments.length == 0) {
            System.out.println("No arguments given.");
            System.exit(0);
        }
        Credentials credentials = credentials();
        try {
            if (credentials.password() == null) {
                System.out.println(
                        "Unrecognized arguments. Use --new-wallet <password> or --decrypt-wallet"
                                + " <password>.");
                return;
            }
            run(credentials.selection(), credentials.password());
        } finally {
            if (credentials.password() != null) {
                Arrays.fill(credentials.password(), '\0');
            }
        }
    }

    /**
     * The action and password the arguments ask for. A null password means the arguments were not
     * understood; the caller reports that rather than guessing an action.
     */
    private Credentials credentials() {
        if (arguments.length == 1) {
            return new Credentials(2, arguments[0].toCharArray());
        }
        if (arguments.length != 2) {
            return new Credentials(2, null);
        }
        if (arguments[0].equals("--new-wallet")) {
            return new Credentials(1, arguments[1].toCharArray());
        }
        if (arguments[0].equals("--decrypt-wallet")) {
            return new Credentials(2, arguments[1].toCharArray());
        }
        return new Credentials(2, null);
    }

    private void run(int selection, char[] password) {
        switch (selection) {
            case 1 -> createWallet(password);
            case 2 -> unlockWallet(password);
            default -> throw new IllegalStateException("Unexpected value: " + selection);
        }
    }

    private void createWallet(char[] password) {
        if (KeyHandler.existsBaseECKeyFromLocal()) {
            System.out.println("Key already exists");
            return;
        }
        if (KeyHandler.calculatePasswordStrength(password) < 9) {
            System.out.println("Bad password.");
            return;
        }
        completeLogin(password, null);
    }

    private void unlockWallet(char[] password) {
        if (!KeyHandler.existsBaseECKeyFromLocal()
                && KeyHandler.calculatePasswordStrength(password) < 9) {
            System.out.println("Bad password.");
            return;
        }
        completeLogin(password, null);
    }

    /** What the arguments ask for: one of the menu selections, and the password. */
    private static final class Credentials {
        private final int selection;
        private final char[] password;

        Credentials(int selection, char[] password) {
            this.selection = selection;
            this.password = password;
        }

        int selection() {
            return selection;
        }

        char[] password() {
            return password;
        }
    }

    private void completeLogin(char[] password, String userMnemonic) {
        CoinInstance.CoinError coinError =
                CoinInstance.getInstance(CoinTicker.BLOCKNET).init(password, userMnemonic, false);
        if (coinError != null) {
            System.out.println(
                    "[master] Error("
                            + coinError.getCode().name()
                            + "): "
                            + coinError.getMessage());
            System.exit(0);
        }

        for (CoinTicker cointicker : CoinTicker.coins()) {
            if (cointicker == CoinTicker.BLOCKNET || cointicker == CoinTicker.BITCOIN) {
                continue;
            }
            coinError = CoinInstance.getInstance(cointicker).init(password, userMnemonic, false);
            if (coinError != null) {
                System.out.println(
                        "["
                                + cointicker.name()
                                + "] Error("
                                + coinError.getCode().name()
                                + "): "
                                + coinError.getMessage());
            }
        }
    }
}
