package io.xlite.daemon.app.net.electrumx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.net.URI;

class ScriptHashesTest {

    private static final String P2PKH_SCRIPT = "76a914111111111111111111111111111111111111111188ac";
    private static final String P2SH_SCRIPT = "a914222222222222222222222222222222222222222287";

    @Test
    void scripthashIsSha256Reversed() {
        assertEquals(
                "0e86768a14a61e71306f240f5b8bb92ced2f1abfb246b82d9356549834c2f6e2",
                ScriptHashes.scripthash(org.bitcoinj.core.Utils.HEX.decode(P2PKH_SCRIPT)));
        assertEquals(
                "fb368a7c183bb0ee53e1a4a4b23a21097ec912175d2c75ae626b08a29259abfa",
                ScriptHashes.scripthash(org.bitcoinj.core.Utils.HEX.decode(P2SH_SCRIPT)));
    }

    @Test
    void hashXIsTheFirstElevenBytesInForwardOrder() {
        assertEquals("e2f6c234985456932db846", ScriptHashes.hashXFromScriptHex(P2PKH_SCRIPT));
        assertNull(ScriptHashes.hashXFromScriptHex("not-hex"));
        assertNull(ScriptHashes.hashXFromScriptHex(""));
    }

    @Test
    void websocketEndpointKeepsSchemeAndAppendsTheCoin() {
        assertEquals(
                URI.create("ws://127.0.0.1:5000/ws/BLOCK"),
                ElectrumEndpoints.websocket("http://127.0.0.1:5000/", "BLOCK"));
        assertEquals(
                URI.create("wss://hub.example/ws/XVG"),
                ElectrumEndpoints.websocket("https://hub.example", "xvg"));
        assertEquals(
                URI.create("ws://hub.example:8080/base/ws/LTC"),
                ElectrumEndpoints.websocket("ws://hub.example:8080/base", "LTC"));
    }

    @Test
    void websocketEndpointRejectsUnknownSchemes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ElectrumEndpoints.websocket("localhost:5000", "BLOCK"));
    }
}
