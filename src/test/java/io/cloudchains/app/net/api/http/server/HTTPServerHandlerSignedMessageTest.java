package io.cloudchains.app.net.api.http.server;

import io.cloudchains.app.net.CoinTicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the per-coin signed-message headers used by signmessage/verifymessage.
 * These strings must match what peer wallets expect — a wrong header produces
 * signatures other tooling rejects (e.g. BTC must NOT inherit Litecoin's).
 */
class HTTPServerHandlerSignedMessageTest {

    @Test
    @DisplayName("BITCOIN uses its own header, not Litecoin's (fallthrough regression)")
    void testSignedMessageHeader_BitcoinIsNotLitecoin() {
        String btc = HTTPServerHandler.signedMessageHeader(CoinTicker.BITCOIN);
        assertEquals("Bitcoin Signed Message:\n", btc);
    }

    @Test
    @DisplayName("each mapped coin keeps its documented header")
    void testSignedMessageHeader_KnownCoins() {
        assertEquals("Blocknet Signed Message:\n", HTTPServerHandler.signedMessageHeader(CoinTicker.BLOCKNET));
        assertEquals("Blocknet Signed Message:\n", HTTPServerHandler.signedMessageHeader(CoinTicker.BLOCKNET_TESTNET5));
        assertEquals("Bitcoin Signed Message:\n", HTTPServerHandler.signedMessageHeader(CoinTicker.BITCOIN_CASH));
        assertEquals("Litecoin Signed Message:\n", HTTPServerHandler.signedMessageHeader(CoinTicker.LITECOIN));
        assertEquals("DarkNet Signed Message:\n", HTTPServerHandler.signedMessageHeader(CoinTicker.PIVX));
        assertEquals("DarkCoin Signed Message:\n", HTTPServerHandler.signedMessageHeader(CoinTicker.DASHCOIN));
        assertEquals("Unobtanium Signed Message:\n", HTTPServerHandler.signedMessageHeader(CoinTicker.UNOBTANIUM));
        assertEquals("Pocketcoin Signed Message:\n", HTTPServerHandler.signedMessageHeader(CoinTicker.PKOIN));
        assertEquals("DigiByte Signed Message:\n", HTTPServerHandler.signedMessageHeader(CoinTicker.DIGIBYTE));
        assertEquals("Raven Signed Message:\n", HTTPServerHandler.signedMessageHeader(CoinTicker.RAVENCOIN));
        assertEquals("Dogecoin Signed Message:\n", HTTPServerHandler.signedMessageHeader(CoinTicker.DOGECOIN));
        assertEquals("Syscoin Signed Message:\n", HTTPServerHandler.signedMessageHeader(CoinTicker.SYSCOIN));
    }

    @Test
    @DisplayName("unmapped tickers return null")
    void testSignedMessageHeader_UnmappedReturnsNull() {
        assertNull(HTTPServerHandler.signedMessageHeader(null));
    }
}
