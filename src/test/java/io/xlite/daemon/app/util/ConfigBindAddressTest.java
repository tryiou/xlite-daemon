package io.xlite.daemon.app.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rpcBindAddress validation and config round-trip.
 *
 * A wallet RPC must bind one specific interface. Wildcards, blanks and
 * unresolvable names fail validation so the server refuses to start loudly
 * instead of listening on every interface. Missing keys migrate silently to
 * loopback, keeping existing datadirs and the GUI working.
 */
public class ConfigBindAddressTest {

    @TempDir
    Path tmp;

    private String savedConfigDir;

    @BeforeEach
    void setup() {
        savedConfigDir = ConfigHelper.CONFIG_DIR;
        ConfigHelper.CONFIG_DIR = tmp.toString();
    }

    @AfterEach
    void restore() {
        ConfigHelper.CONFIG_DIR = savedConfigDir;
    }

    private File settingsFile(String ticker) {
        File dir = new File(new File(tmp.toFile(), ConfigHelper.DATA_DIR_NAME), "settings");
        dir.mkdirs();
        return new File(dir, "config-" + ticker + ".json");
    }

    @Test
    void testValidationMatrix() {
        assertTrue(ConfigHelper.isBindAddressValid("127.0.0.1"));
        assertTrue(ConfigHelper.isBindAddressValid("::1"));
        assertTrue(ConfigHelper.isBindAddressValid("localhost"));
        assertTrue(ConfigHelper.isBindAddressValid("192.168.1.10"));
        assertFalse(ConfigHelper.isBindAddressValid("0.0.0.0"));
        assertFalse(ConfigHelper.isBindAddressValid("::"));
        assertFalse(ConfigHelper.isBindAddressValid(""));
        assertFalse(ConfigHelper.isBindAddressValid("   "));
        assertFalse(ConfigHelper.isBindAddressValid(null));
        assertFalse(ConfigHelper.isBindAddressValid("no.such.host.invalid"));
    }

    @Test
    void testMissingKeyMigratesToLoopback() throws Exception {
        File f = settingsFile("BINDMIGRATE");
        Files.writeString(f.toPath(), "{\"rpcPort\": 41419}");

        ConfigHelper helper = new ConfigHelper("BINDMIGRATE");
        assertEquals(ConfigHelper.DEFAULT_RPC_BIND_ADDRESS, helper.getRpcBindAddress());
        assertTrue(helper.validBindAddress());
        assertTrue(Files.readString(f.toPath()).contains("\"rpcBindAddress\""));
    }

    @Test
    void testWildcardKeptButInvalid() throws Exception {
        File f = settingsFile("BINDWILD");
        Files.writeString(f.toPath(), "{\"rpcPort\": 41419, \"rpcBindAddress\": \"0.0.0.0\"}");

        ConfigHelper helper = new ConfigHelper("BINDWILD");
        assertEquals("0.0.0.0", helper.getRpcBindAddress());
        assertFalse(helper.validBindAddress());
    }

    @Test
    void testExplicitAddressKeptAndValid() throws Exception {
        File f = settingsFile("BINDEXPL");
        Files.writeString(f.toPath(), "{\"rpcPort\": 41419, \"rpcBindAddress\": \"192.168.1.10\"}");

        ConfigHelper helper = new ConfigHelper("BINDEXPL");
        assertEquals("192.168.1.10", helper.getRpcBindAddress());
        assertTrue(helper.validBindAddress());
    }

    @Test
    void testPaddedAddressNormalizedOnRead() throws Exception {
        File f = settingsFile("BINDPAD");
        Files.writeString(f.toPath(), "{\"rpcPort\": 41419, \"rpcBindAddress\": \"  127.0.0.1  \"}");

        ConfigHelper helper = new ConfigHelper("BINDPAD");
        assertEquals("127.0.0.1", helper.getRpcBindAddress());
        assertTrue(helper.validBindAddress());
    }
}
