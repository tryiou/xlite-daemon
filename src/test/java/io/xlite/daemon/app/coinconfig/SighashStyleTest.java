package io.xlite.daemon.app.coinconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The signing policy is compiled in and only cross-checked against the configuration repository, so
 * the mapping between the two and the supplements they must agree with are pinned here.
 */
class SighashStyleTest {

    @Test
    void mapsTheConfigurationTransactionBuilderToItsSignatureHash() {
        assertEquals(SighashStyle.LEGACY, SighashStyle.fromCreateTxMethod("BTC"));
        assertEquals(SighashStyle.FORKID, SighashStyle.fromCreateTxMethod("BCH"));
        assertEquals(SighashStyle.LEGACY, SighashStyle.fromCreateTxMethod("btc"));
        assertEquals(SighashStyle.FORKID, SighashStyle.fromCreateTxMethod(" bch "));
    }

    @Test
    void anUnsupportedBuilderHasNoStyle() {
        assertNull(SighashStyle.fromCreateTxMethod("PART"));
        assertNull(SighashStyle.fromCreateTxMethod(null));
        assertNull(SighashStyle.fromCreateTxMethod(""));
    }

    @Test
    void aCompiledStyleAgreesOnlyWithItsOwnBuilder() {
        assertTrue(SighashStyle.FORKID.matchesCreateTxMethod("BCH"));
        assertFalse(SighashStyle.FORKID.matchesCreateTxMethod("BTC"));
        assertFalse(SighashStyle.FORKID.matchesCreateTxMethod("PART"));
        assertTrue(SighashStyle.LEGACY.matchesCreateTxMethod("BTC"));
        assertFalse(SighashStyle.LEGACY.matchesCreateTxMethod("BCH"));
        assertFalse(SighashStyle.LEGACY.matchesCreateTxMethod(null));
    }

    @Test
    void bitcoinCashCompilesToForkIdAndBitcoinToLegacy() {
        assertEquals(SighashStyle.FORKID, CompiledCoinSupplement.forTicker("BCH").sighashStyle());
        assertEquals(SighashStyle.LEGACY, CompiledCoinSupplement.forTicker("BTC").sighashStyle());
        assertEquals(SighashStyle.LEGACY, CompiledCoinSupplement.forTicker("BTX").sighashStyle());
        assertEquals(SighashStyle.LEGACY, CompiledCoinSupplement.forTicker("XVG").sighashStyle());
    }
}
