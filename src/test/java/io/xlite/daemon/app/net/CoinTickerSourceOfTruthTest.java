package io.xlite.daemon.app.net;

import io.xlite.daemon.app.coinconfig.CompiledCoinSupplement;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@link CoinTicker} enum is the single source of truth for supported
 * coins. Everything else derives: active set, string mappings, compiled
 * chain supplements. Adding/delisting a coin is one enum edit; any
 * hand-maintained parallel list drifting from it fails here.
 */
public class CoinTickerSourceOfTruthTest {

    @Test
    void testActiveTickersMatchEnum() {
        // The full explicit active set: a dropped/added middle coin changes
        // this set and fails here (a purely derived expectation would shrink
        // together with production and pass silently).
        Set<CoinTicker> expected = EnumSet.of(CoinTicker.BLOCKNET, CoinTicker.BITCOIN, CoinTicker.LITECOIN,
                CoinTicker.DASHCOIN, CoinTicker.DOGECOIN, CoinTicker.SYSCOIN, CoinTicker.PIVX,
                CoinTicker.DIGIBYTE, CoinTicker.RAVENCOIN, CoinTicker.UNOBTANIUM, CoinTicker.PKOIN);
        Set<CoinTicker> active = new HashSet<>(Arrays.asList(CoinTickerUtils.getActiveTickers()));
        assertEquals(expected, active);
        // Spare rows for inactive coins (e.g. the BCH supplement entry, kept
        // for future activation) are intentional and NOT flagged here; only
        // the active set is pinned.
        for (CoinTicker t : CoinTickerUtils.getActiveTickers()) {
            assertTrue(CoinTickerUtils.isActiveTicker(t));
        }
    }

    @Test
    void testEveryActiveTickerResolvesBothWays() {
        for (CoinTicker t : CoinTickerUtils.getActiveTickers()) {
            String s = CoinTickerUtils.tickerToString(t);
            assertNotNull(s, t.name());
            assertEquals(t, CoinTickerUtils.stringToTicker(s));
        }
    }

    @Test
    void testEveryActiveCoinHasCompiledSupplement() {
        // BLOCKNET uses hardcoded network parameters (CoinInstance init);
        // all others must have a supplement row or wallet init fails with
        // UNSUPPORTEDCOIN at runtime.
        for (CoinTicker t : CoinTickerUtils.getActiveTickers()) {
            if (t == CoinTicker.BLOCKNET) {
                continue;
            }
            assertDoesNotThrow(() -> CompiledCoinSupplement.forTicker(CoinTickerUtils.tickerToString(t)), t.name());
        }
    }
}
