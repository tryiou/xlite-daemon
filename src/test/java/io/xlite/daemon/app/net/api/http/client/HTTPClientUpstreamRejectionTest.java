package io.xlite.daemon.app.net.api.http.client;

import com.google.gson.JsonObject;
import io.xlite.daemon.app.net.CoinTicker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session rejection memory for coins with no upstream plugin.
 *
 * In legacy mode the backend is singular, so one definitive rejection
 * ({@code -32000} + {@code "No utxo-plugin configured for coin …"}) answers
 * every later request for that (family, coin) pair without opening a socket.
 * Anything less definitive — transient codes, the backend's other
 * {@code -32000} shapes, address-level failures, malformed payloads — must
 * never be cached: the next poll retries as before (fail-open).
 */
public class HTTPClientUpstreamRejectionTest {

    private static final CoinTicker COIN = CoinTicker.DOGECOIN;

    private static final String DEFINITIVE =
            "{\"result\":null,\"error\":{\"code\":-32000,"
                    + "\"message\":\"No utxo-plugin configured for coin DOGE\"}}";
    private static final String DEFINITIVE_BARE =
            "{\"code\":-32000,\"message\":\"No utxo-plugin configured for coin DOGE\"}";
    private static final String WRONG_CODE_WITH_MARKER =
            "{\"result\":null,\"error\":{\"code\":-1,"
                    + "\"message\":\"No utxo-plugin configured for coin DOGE\"}}";
    private static final String UNREACHABLE_SAME_CODE =
            "{\"result\":null,\"error\":{\"code\":-32000,"
                    + "\"message\":\"utxo-plugin unreachable for coin DOGE\"}}";
    private static final String ADDRESSES_FAILED_SAME_CODE =
            "{\"result\":null,\"error\":{\"code\":-32000,\"message\":\"All addresses failed\"}}";
    private static final String SUCCESS =
            "{\"result\":{\"hex\":\"aa\"},\"error\":null}";

    /** Canned backend: overrides the package-private transport, no network. */
    static class CountingClient extends HTTPClient {
        int calls;
        String canned;
        boolean exrMode;

        CountingClient() {
            super(2);
        }

        @Override
        boolean useEXR() {
            return exrMode;
        }

        @Override
        String executeRequest(String endpoint, JsonObject params) {
            calls++;
            return canned;
        }
    }

    @BeforeEach
    void clearBefore() {
        HTTPClient.clearUpstreamRejectionsForTest();
    }

    @AfterEach
    void clearAfter() {
        HTTPClient.clearUpstreamRejectionsForTest();
    }

    @Test
    void testRecord_acceptsProducerEnvelope() {
        assertTrue(HTTPClient.recordUpstreamRejection(
                HTTPClient.UPSTREAM_FAMILY_UTXO, COIN, DEFINITIVE));
        assertTrue(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_UTXO, COIN));
    }

    @Test
    void testRecord_acceptsBareShape() {
        assertTrue(HTTPClient.recordUpstreamRejection(
                HTTPClient.UPSTREAM_FAMILY_TX, COIN, DEFINITIVE_BARE));
        assertTrue(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_TX, COIN));
    }

    @Test
    void testRecord_rejectsWrongCodeWithMarker() {
        assertFalse(HTTPClient.recordUpstreamRejection(
                HTTPClient.UPSTREAM_FAMILY_UTXO, COIN, WRONG_CODE_WITH_MARKER));
        assertFalse(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_UTXO, COIN));
    }

    @Test
    void testRecord_rejectsUnreachableSameCode() {
        // Same code as definitive, but the plugin may be restarting: transient.
        assertFalse(HTTPClient.recordUpstreamRejection(
                HTTPClient.UPSTREAM_FAMILY_UTXO, COIN, UNREACHABLE_SAME_CODE));
        assertFalse(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_UTXO, COIN));
    }

    @Test
    void testRecord_rejectsAllAddressesFailed() {
        // Address-level failure, never coin-level: must not suppress the coin.
        assertFalse(HTTPClient.recordUpstreamRejection(
                HTTPClient.UPSTREAM_FAMILY_UTXO, COIN, ADDRESSES_FAILED_SAME_CODE));
        assertFalse(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_UTXO, COIN));
    }

    @Test
    void testRecord_rejectsMalformedShapes() {
        assertFalse(HTTPClient.recordUpstreamRejection(HTTPClient.UPSTREAM_FAMILY_UTXO, COIN, null));
        assertFalse(HTTPClient.recordUpstreamRejection(HTTPClient.UPSTREAM_FAMILY_UTXO, COIN, "{nope"));
        assertFalse(HTTPClient.recordUpstreamRejection(HTTPClient.UPSTREAM_FAMILY_UTXO, COIN, "[1,2]"));
        assertFalse(HTTPClient.recordUpstreamRejection(
                HTTPClient.UPSTREAM_FAMILY_UTXO, COIN, "{\"result\":1}"));
        assertFalse(HTTPClient.recordUpstreamRejection(
                HTTPClient.UPSTREAM_FAMILY_UTXO, COIN,
                "{\"result\":null,\"error\":\"boom\"}"));
        assertFalse(HTTPClient.recordUpstreamRejection(
                HTTPClient.UPSTREAM_FAMILY_UTXO, COIN,
                "{\"result\":null,\"error\":{\"code\":\"-32000\",\"message\":\""
                        + HTTPClient.NO_UPSTREAM_MARKER + "DOGE\"}}"));
        assertFalse(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_UTXO, COIN));
    }

    @Test
    void testGating_secondCallIssuesNoHttp() {
        CountingClient client = new CountingClient();
        client.canned = DEFINITIVE;

        assertNull(client.getRawTransaction(COIN, "abc", true));
        assertEquals(1, client.calls);
        assertTrue(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_TX, COIN));

        // Backend recovered (or any other payload): still no socket this session.
        client.canned = SUCCESS;
        assertNull(client.getRawTransaction(COIN, "abc", true));
        assertEquals(1, client.calls);
    }

    @Test
    void testFamilies_independent() {
        assertTrue(HTTPClient.recordUpstreamRejection(
                HTTPClient.UPSTREAM_FAMILY_UTXO, COIN, DEFINITIVE));
        assertTrue(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_UTXO, COIN));
        assertFalse(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_HISTORY, COIN));
        assertFalse(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_TX, COIN));
        assertFalse(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_BLOCK, COIN));
    }

    @Test
    void testClear_resets() {
        assertTrue(HTTPClient.recordUpstreamRejection(
                HTTPClient.UPSTREAM_FAMILY_UTXO, COIN, DEFINITIVE));
        HTTPClient.clearUpstreamRejectionsForTest();
        assertFalse(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_UTXO, COIN));
    }

    @Test
    void testExrMode_neverRecords() {
        // EXR selects among multiple servers: a global record could suppress a
        // working one, so recording and gating stay legacy-only.
        CountingClient client = new CountingClient();
        client.exrMode = true;
        client.canned = DEFINITIVE;

        assertNull(client.getRawTransaction(COIN, "abc", true));
        assertFalse(HTTPClient.isUpstreamRejected(HTTPClient.UPSTREAM_FAMILY_TX, COIN));

        assertNull(client.getRawTransaction(COIN, "abc", true));
        assertEquals(2, client.calls);
    }
}
