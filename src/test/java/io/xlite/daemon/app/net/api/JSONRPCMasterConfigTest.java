package io.xlite.daemon.app.net.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.xlite.daemon.app.util.ConfigHelper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * The master listener needs credentials and an enabled flag before it binds.
 *
 * <p>A fresh install has neither: the GUI connects with the master credentials from the
 * configuration, so a listener that starts first and writes later leaves the first start without a
 * usable endpoint. {@link JSONRPCController#masterConfig()} must therefore materialize both on
 * disk, and must not replace credentials that an installer or the GUI already wrote.
 */
class JSONRPCMasterConfigTest {

    @TempDir static Path tempConfigDir;

    /** Restored after the class so a later class never sees a deleted path. */
    private static String previousConfigDir;

    @BeforeAll
    static void isolateConfig() {
        previousConfigDir = ConfigHelper.getConfigDir();
        ConfigHelper.setConfigDir(tempConfigDir.toString());
    }

    @AfterAll
    static void restoreConfig() {
        JSONRPCController.resetForTest();
        ConfigHelper.setConfigDir(previousConfigDir);
    }

    @Test
    void materializesCredentialsAndListenerBeforeBinding() {
        ConfigHelper config = JSONRPCController.masterConfig();

        assertTrue(config.validAuth(), "a master listener without credentials cannot authenticate");
        assertTrue(config.isRpcEnabled(), "the master listener must be enabled before it binds");

        ConfigHelper reread = new ConfigHelper(ConfigHelper.MASTER_TICKER);
        assertTrue(reread.validAuth(), "the credentials must be on disk, not only in memory");
        assertTrue(reread.isRpcEnabled());
        assertEquals(config.getRpcUsername(), reread.getRpcUsername());
        assertEquals(config.getRpcPassword(), reread.getRpcPassword());
    }

    @Test
    void keepsCredentialsWrittenByTheInstallerOrGui() {
        ConfigHelper existing = new ConfigHelper(ConfigHelper.MASTER_TICKER);
        existing.setRpcUsername("kept-user");
        existing.setRpcPassword("kept-password");
        existing.writeConfig();

        ConfigHelper config = JSONRPCController.masterConfig();

        assertEquals("kept-user", config.getRpcUsername(), "existing credentials must not churn");
        assertEquals("kept-password", config.getRpcPassword());
        assertTrue(config.isRpcEnabled());
    }
}
