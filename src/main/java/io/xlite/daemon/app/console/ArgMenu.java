package io.xlite.daemon.app.console;

import io.xlite.daemon.app.crypto.KeyHandler;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;

import java.util.Arrays;

public class ArgMenu {
    private String[] arguments;

    public ArgMenu(String[] args) {
        this.arguments = args;
    }

    public void init() {
        int selection = 2;
        char[] password = null;

        System.out.println("-------------------------");
        System.out.println("Help: ");
        System.out.println("Create new wallet: --new-wallet (password)");
        System.out.println("Decrypt wallet: --decrypt-wallet (password)");

        if (arguments.length == 0) {
            System.out.println("No arguments given.");
            System.exit(0);
        } else {
            if (arguments.length == 1) {
                password = arguments[0].toCharArray();
            } else if (arguments.length == 2 && arguments[0].equals("--new-wallet")) {
                selection = 1;
                password = arguments[1].toCharArray();
            } else if (arguments.length == 2 && arguments[0].equals("--decrypt-wallet")) {
                password = arguments[1].toCharArray();
            }
        }

        try {
            if (password == null) {
                System.out.println("Unrecognized arguments. Use --new-wallet <password> or --decrypt-wallet <password>.");
                return;
            }
            switch (selection) {
                case 1: {
                    if (KeyHandler.existsBaseECKeyFromLocal()) {
                        System.out.println("Key already exists");
                        return;
                    }

                    int strength = KeyHandler.calculatePasswordStrength(password);

                    if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                        System.out.println("Bad password.");
                        return;
                    }

                    completeLogin(password, null);
                    break;
                }
                case 2: {
                    int strength = KeyHandler.calculatePasswordStrength(password);

                    if (!KeyHandler.existsBaseECKeyFromLocal() && strength < 9) {
                        System.out.println("Bad password.");
                        return;
                    }

                    completeLogin(password, null);
                    break;
                }
                case 3: {
                    System.out.println("Exiting...");
                    System.exit(0);
                }
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + selection);
            }
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private void completeLogin(char[] password, String userMnemonic) {
        CoinInstance.CoinError coinError = CoinInstance.getInstance(CoinTicker.BLOCKNET).init(password, userMnemonic, false);
        if (coinError != null) {
            System.out.println("[master] Error(" + coinError.getCode().name() + "): " + coinError.getMessage());
            System.exit(0);
        }

        for (CoinTicker cointicker : CoinTicker.coins()) {
            if (cointicker == CoinTicker.BLOCKNET || cointicker == CoinTicker.BLOCKNET_TESTNET5 || cointicker == CoinTicker.BITCOIN)
                continue;
            coinError = CoinInstance.getInstance(cointicker).init(password, userMnemonic, false);
            if (coinError != null)
                System.out.println("[" + cointicker.name() + "] Error(" + coinError.getCode().name() + "): " + coinError.getMessage());
        }
    }
}
