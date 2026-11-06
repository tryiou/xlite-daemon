package io.xlite.daemon.app.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Startup flags are a pre-pass over the whole command line.
 *
 * <p>They used to be handled while walking the wallet actions, so the first {@code --password}
 * ended processing and any flag after it was silently ignored. The pre-pass must consume provider
 * and configuration flags wherever they appear and leave only the wallet actions for the later
 * pass.
 */
class ConsoleMenuStartupFlagsTest {

    @Test
    void startupFlagAfterPasswordIsConsumedBeforeWalletActions() {
        ConsoleMenu menu =
                new ConsoleMenu(
                        new String[] {
                            "--password",
                            "test-password",
                            "--blockchain-configuration-files",
                            "/nonexistent/configs"
                        });

        List<String> walletArguments = menu.applyStartupFlags();

        assertEquals(
                List.of("--password", "test-password"),
                walletArguments,
                "the flag and its value must not leak into the wallet actions");
        assertFalse(
                walletArguments.contains("--blockchain-configuration-files"),
                "the flag was never applied");
        assertFalse(
                walletArguments.contains("/nonexistent/configs"),
                "the flag value was never applied");
    }

    @Test
    void startupFlagBeforePasswordIsHandledTheSameWay() {
        ConsoleMenu menu =
                new ConsoleMenu(
                        new String[] {
                            "--blockchain-configuration-files",
                            "/nonexistent/configs",
                            "--password",
                            "test-password"
                        });

        assertEquals(List.of("--password", "test-password"), menu.applyStartupFlags());
    }
}
