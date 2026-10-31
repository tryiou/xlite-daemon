package io.xlite.daemon.app.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class XRouterConfigurationTest {

    private static final String CONFIG =
            """
            [Main]
            wallets=BLOCK,LTC
            timeout=30
            blocklimit=120
            paymentaddress=sXu5Jxexample

            [xrService]
            fee=0.01

            [xrGetBlockCount]
            fee=0.0001
            """;

    @Test
    void readsTheMainSectionAndPerCommandFees() {
        XRouterConfiguration config = new XRouterConfiguration(CONFIG);

        config.parseConfig();

        assertEquals(List.of("BLOCK", "LTC"), config.getSupportedWallets());
        assertEquals(30, config.getTimeout());
        assertEquals(120, config.getBlockLimit());
        assertEquals("sXu5Jxexample", config.getFeeAddress());
        assertEquals(Map.of("xrService", 0.01, "xrGetBlockCount", 0.0001), config.getFeeMap());
    }

    @Test
    void keepsPropertyEscapesInsideASection() {
        XRouterConfiguration config =
                new XRouterConfiguration(
                        "[Main]\nwallets=BLOCK\ntimeout=1\nblocklimit=2\npaymentaddress=a\\tb\n");

        config.parseConfig();

        assertEquals("a\tb", config.getFeeAddress());
    }

    @Test
    void leavesTheParsedCollectionsReadOnly() {
        XRouterConfiguration config = new XRouterConfiguration(CONFIG);
        config.parseConfig();

        assertThrows(
                UnsupportedOperationException.class, () -> config.getSupportedWallets().add("BTC"));
        assertThrows(
                UnsupportedOperationException.class, () -> config.getFeeMap().put("xrOther", 1.0));
    }

    @Test
    void emptyConfigLeavesDefaults() {
        XRouterConfiguration config = new XRouterConfiguration("");

        config.parseConfig();

        assertTrue(config.getSupportedWallets().isEmpty());
        assertTrue(config.getFeeMap().isEmpty());
    }
}
