package io.xlite.daemon.app.net.api.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.xlite.daemon.app.net.CoinTicker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-coin signed-message headers used by signmessage/verifymessage. These strings must
 * match what peer wallets expect — a wrong header produces signatures other tooling rejects (e.g.
 * BTC must NOT inherit Litecoin's).
 */
class SignedMessagesTest {

    @Test
    @DisplayName("BITCOIN uses its own header, not Litecoin's (fallthrough regression)")
    void testSignedMessageHeader_BitcoinIsNotLitecoin() {
        String btc = SignedMessages.header(CoinTicker.BITCOIN);
        assertEquals("Bitcoin Signed Message:\n", btc);
    }

    @Test
    @DisplayName("each mapped coin keeps its documented header")
    void testSignedMessageHeader_KnownCoins() {
        assertEquals("Blocknet Signed Message:\n", SignedMessages.header(CoinTicker.BLOCKNET));
        assertEquals("Bitcoin Signed Message:\n", SignedMessages.header(CoinTicker.BITCOIN_CASH));
        assertEquals("Litecoin Signed Message:\n", SignedMessages.header(CoinTicker.LITECOIN));
        assertEquals("DarkNet Signed Message:\n", SignedMessages.header(CoinTicker.PIVX));
        assertEquals("DarkCoin Signed Message:\n", SignedMessages.header(CoinTicker.DASHCOIN));
        assertEquals("Unobtanium Signed Message:\n", SignedMessages.header(CoinTicker.UNOBTANIUM));
        assertEquals("Pocketcoin Signed Message:\n", SignedMessages.header(CoinTicker.PKOIN));
        assertEquals("DigiByte Signed Message:\n", SignedMessages.header(CoinTicker.DIGIBYTE));
        assertEquals("Raven Signed Message:\n", SignedMessages.header(CoinTicker.RAVENCOIN));
        assertEquals("Dogecoin Signed Message:\n", SignedMessages.header(CoinTicker.DOGECOIN));
        assertEquals("Syscoin Signed Message:\n", SignedMessages.header(CoinTicker.SYSCOIN));
        assertEquals("VERGE Signed Message:\n", SignedMessages.header(CoinTicker.VERGE));
        assertEquals("BitCore Signed Message:\n", SignedMessages.header(CoinTicker.BITCORE));
    }

    @Test
    @DisplayName("a missing ticker is refused rather than producing a header")
    void testSignedMessageHeader_RequiresATicker() {
        assertThrows(IllegalArgumentException.class, () -> SignedMessages.header(null));
    }
}
