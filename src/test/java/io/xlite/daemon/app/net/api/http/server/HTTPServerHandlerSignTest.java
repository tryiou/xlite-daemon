package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.subgraph.orchid.encoders.Hex;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.xlite.daemon.app.Version;
import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.ConfigHelper;
import io.xlite.daemon.app.util.TxTimeField;
import io.xlite.daemon.app.util.TxTimeFieldSigner;
import io.xlite.daemon.app.util.UTXO;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The signrawtransaction contract: the version BCF assigns survives signing,
 * and the nTime that was signed is the one the wire bytes carry.
 *
 * <p>Signing must not rewrite the transaction's version or locktime: the caller
 * built the transaction from the coin's configuration, and the signed bytes
 * have to match. On a chain that enforces the input-time rule (Verge), the
 * signed nTime is the later of the unsigned transaction's value and the newest
 * parent's nTime; an unfetchable parent fails the call instead of producing a
 * transaction the node rejects at consensus.
 *
 * <p>Signature verification goes through {@link TxTimeFieldSigner#chainSighash}
 * and {@link TxTimeFieldSigner#verify}, so the assertion is that the returned
 * bytes verify against the nTime the response carries -- not merely that the
 * handler returned something.
 */
class HTTPServerHandlerSignTest {

    private static final String USER = "testuser";
    private static final String PASS = "testpass";
    private static final long INCOMING_NTIME = 1_790_592_305L;

    private final CoinInstance coin = mock(CoinInstance.class);
    private final HTTPClient httpClient = mock(HTTPClient.class);

    @BeforeAll
    static void loadRegistry() {
        Map<String, CoinConfig> cfgs = new LinkedHashMap<>();
        cfgs.put("XVG", config("XVG", 30, 33, 158, 1_000_000L, 20102, 1, true));
        cfgs.put("PKOIN", config("PKOIN", 55, 80, 33, 100_000_000L, 37071, 2, true));
        cfgs.put("LTC", config("LTC", 48, 50, 176, 100_000_000L, 9332, 2, false));
        CoinConfigRegistry.loadForTest(cfgs);
    }

    @AfterAll
    static void resetRegistry() {
        CoinConfigRegistry.resetForTest();
    }

    private static CoinConfig config(String ticker, int addr, int p2sh, int dpk,
            long coinFactor, int port, int txVersion, boolean timeField) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("AddressPrefix", Integer.toString(addr));
        e.put("ScriptPrefix", Integer.toString(p2sh));
        e.put("SecretPrefix", Integer.toString(dpk));
        e.put("COIN", Long.toString(coinFactor));
        e.put("FeePerByte", "2");
        e.put("MinTxFee", "678");
        e.put("Port", Integer.toString(port));
        e.put("DustAmount", "0");
        e.put("TxVersion", Integer.toString(txVersion));
        e.put("TxWithTimeField", Boolean.toString(timeField));
        return new CoinConfig(ticker, "Test " + ticker, ticker.toLowerCase() + "--test", e);
    }

    private static String hex(byte[] bytes) {
        return new String(Hex.encode(bytes));
    }

    private static byte[] bytes(String hex) {
        return Hex.decode(hex);
    }

    private static NetworkParameters paramsFor(CoinTicker ticker) {
        return ConfigurableNetworkParameters.from(
                CoinConfigRegistry.get(CoinTickerUtils.tickerToString(ticker)));
    }

    /** A wallet holding one P2PKH output, plus the unsigned transaction spending it. */
    private static final class Fixture {
        final NetworkParameters params;
        final Script spentScript;
        final UTXO utxo;
        final AddressBalance addressBalance;
        final byte[] unsignedWireBytes;

        Fixture(CoinTicker ticker, boolean timeField, int txVersion, long incomingNTime) {
            ECKey key = new ECKey();
            params = paramsFor(ticker);
            LegacyAddress address = LegacyAddress.fromKey(params, key);
            spentScript = ScriptBuilder.createOutputScript(address);

            String parentTxid = "11".repeat(32);
            utxo = new UTXO(ticker, address.toBase58(), parentTxid, 0, 100, 10_000_000L);
            addressBalance = new AddressBalance(address, key.getPrivateKeyEncoded(params));
            assertTrue(addressBalance.addUtxo(utxo));

            Transaction tx = new Transaction(params);
            tx.setVersion(txVersion);
            tx.addInput(new TransactionInput(params, tx, new byte[0],
                    new TransactionOutPoint(params, 0, Sha256Hash.wrap(parentTxid))));
            tx.addOutput(new TransactionOutput(params, tx, Coin.valueOf(9_000_000L),
                    LegacyAddress.fromKey(params, new ECKey())));

            byte[] standard = tx.bitcoinSerialize();
            unsignedWireBytes = timeField
                    ? TxTimeField.insertNTime(standard, incomingNTime)
                    : standard;
        }
    }

    /** A parent raw transaction carrying {@code nTime}, as getrawtransaction returns it. */
    private static JsonObject parentResponse(NetworkParameters params, long nTime) {
        Transaction parent = new Transaction(params);
        parent.addOutput(new TransactionOutput(params, parent, Coin.valueOf(1L),
                LegacyAddress.fromKey(params, new ECKey())));
        JsonObject response = new JsonObject();
        response.addProperty("result",
                hex(TxTimeField.insertNTime(parent.bitcoinSerialize(), nTime)));
        return response;
    }

    /**
     * Static mocks for the two {@link CoinInstance} statics the handler touches
     * indirectly: {@code UTXO.createUTXO()} resolves the coin instance by
     * ticker, and the response writer reads the daemon version string. A static
     * mock answers everything else with a default, so both are stubbed.
     */
    private MockedStatic<CoinInstance> staticCoinInstance() {
        MockedStatic<CoinInstance> instances = mockStatic(CoinInstance.class);
        instances.when(() -> CoinInstance.getInstance(any())).thenReturn(coin);
        instances.when(CoinInstance::getVersionString).thenReturn(Version.SUBVERSION);
        return instances;
    }

    private HTTPServerHandler newHandler(CoinTicker ticker, Fixture fixture) throws Exception {
        ConfigHelper configHelper = mock(ConfigHelper.class);
        when(coin.getConfigHelper()).thenReturn(configHelper);
        when(configHelper.getRpcUsername()).thenReturn(USER);
        when(configHelper.getRpcPassword()).thenReturn(PASS);
        when(coin.getTicker()).thenReturn(ticker);
        when(coin.isInstanceRunning()).thenReturn(true);
        when(coin.getNetworkParameters()).thenReturn(fixture.params);
        when(coin.getAddressKeyPairs()).thenReturn(List.of(fixture.addressBalance));

        HTTPServerHandler handler = new HTTPServerHandler(coin);
        Field field = HTTPServerHandler.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(handler, httpClient);
        return handler;
    }

    private FullHttpResponse sign(HTTPServerHandler handler, byte[] unsignedWireBytes) {
        JsonArray params = new JsonArray();
        params.add(hex(unsignedWireBytes));
        JsonObject body = new JsonObject();
        body.addProperty("method", "signrawtransaction");
        body.add("params", params);

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Unpooled.wrappedBuffer(bytes));
        String credentials = Base64.getEncoder().encodeToString(
                (USER + ":" + PASS).getBytes(StandardCharsets.UTF_8));
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Basic " + credentials);
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);

        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            channel.writeInbound(request);
            channel.flush();
            return channel.readOutbound();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static JsonObject envelope(FullHttpResponse response) {
        assertNotNull(response, "handler dropped the connection");
        return JsonParser.parseString(
                response.content().toString(CharsetUtil.UTF_8)).getAsJsonObject();
    }

    private static Transaction parseWire(CoinTicker ticker, NetworkParameters params, String wireHex) {
        return HTTPServerHandler.parseForCoin(ticker, params, bytes(wireHex));
    }

    private static void assertSignatureCovers(CoinTicker ticker, Fixture fixture,
            String wireHex, long expectedNTime) {
        Transaction signed = parseWire(ticker, fixture.params, wireHex);
        Sha256Hash hash = TxTimeFieldSigner.chainSighash(
                signed, fixture.spentScript, 0, Transaction.SigHash.ALL.value, expectedNTime);
        assertTrue(TxTimeFieldSigner.verify(signed.getInputs().get(0).getScriptSig(), hash),
                "the signature must verify against the nTime carried on the wire");
    }

    @Test
    @DisplayName("Verge: a newer parent raises the signed nTime, and the wire carries it")
    void testVergeRaisesNTimeToNewestParent() throws Exception {
        Fixture fixture = new Fixture(CoinTicker.VERGE, true, 1, INCOMING_NTIME);
        long parentNTime = INCOMING_NTIME + 60;
        when(httpClient.getRawTransaction(eq(CoinTicker.VERGE), anyString(), anyBoolean()))
                .thenReturn(parentResponse(fixture.params, parentNTime));

        try (MockedStatic<CoinInstance> ignored = staticCoinInstance()) {
            FullHttpResponse response = sign(newHandler(CoinTicker.VERGE, fixture),
                    fixture.unsignedWireBytes);

            JsonObject result = envelope(response).getAsJsonObject("result");
            assertNotNull(result, "a valid parent must produce a signed transaction");
            String wireHex = result.get("hex").getAsString();
            assertTrue(result.get("complete").getAsBoolean());

            assertEquals(parentNTime, TxTimeField.parentNTime(bytes(wireHex)));
            assertEquals(1, parseWire(CoinTicker.VERGE, fixture.params, wireHex).getVersion(),
                    "the BCF version must survive signing");
            assertSignatureCovers(CoinTicker.VERGE, fixture, wireHex, parentNTime);
        }
    }

    @Test
    @DisplayName("Verge: an older parent leaves the incoming nTime untouched")
    void testVergeKeepsIncomingNTimeWhenParentsAreOlder() throws Exception {
        Fixture fixture = new Fixture(CoinTicker.VERGE, true, 1, INCOMING_NTIME);
        when(httpClient.getRawTransaction(eq(CoinTicker.VERGE), anyString(), anyBoolean()))
                .thenReturn(parentResponse(fixture.params, INCOMING_NTIME - 60));

        try (MockedStatic<CoinInstance> ignored = staticCoinInstance()) {
            FullHttpResponse response = sign(newHandler(CoinTicker.VERGE, fixture),
                    fixture.unsignedWireBytes);

            String wireHex = envelope(response).getAsJsonObject("result").get("hex").getAsString();
            assertEquals(INCOMING_NTIME, TxTimeField.parentNTime(bytes(wireHex)),
                    "wall-clock time must not be injected while signing");
            assertSignatureCovers(CoinTicker.VERGE, fixture, wireHex, INCOMING_NTIME);
        }
    }

    @Test
    @DisplayName("Verge: an unfetchable parent fails the call instead of guessing a time")
    void testVergeUnfetchableParentFailsClosed() throws Exception {
        Fixture fixture = new Fixture(CoinTicker.VERGE, true, 1, INCOMING_NTIME);
        when(httpClient.getRawTransaction(eq(CoinTicker.VERGE), anyString(), anyBoolean()))
                .thenReturn(null);

        try (MockedStatic<CoinInstance> ignored = staticCoinInstance()) {
            FullHttpResponse response = sign(newHandler(CoinTicker.VERGE, fixture),
                    fixture.unsignedWireBytes);

            JsonObject envelope = envelope(response);
            assertTrue(envelope.get("result").isJsonNull(), "no hex may be returned");
            assertEquals(-6, envelope.getAsJsonObject("error").get("code").getAsInt());
        }
    }

    @Test
    @DisplayName("Pocketcoin: version 2 is preserved and no parent is fetched")
    void testPkoinPreservesVersionWithoutParentFetch() throws Exception {
        Fixture fixture = new Fixture(CoinTicker.PKOIN, true, 2, INCOMING_NTIME);

        try (MockedStatic<CoinInstance> ignored = staticCoinInstance()) {
            FullHttpResponse response = sign(newHandler(CoinTicker.PKOIN, fixture),
                    fixture.unsignedWireBytes);

            JsonObject result = envelope(response).getAsJsonObject("result");
            String wireHex = result.get("hex").getAsString();
            assertEquals(2, parseWire(CoinTicker.PKOIN, fixture.params, wireHex).getVersion());
            assertEquals(INCOMING_NTIME, TxTimeField.parentNTime(bytes(wireHex)));
            assertSignatureCovers(CoinTicker.PKOIN, fixture, wireHex, INCOMING_NTIME);
            verify(httpClient, never()).getRawTransaction(any(), anyString(), anyBoolean());
        }
    }

    @Test
    @DisplayName("a non-time-field coin keeps its version and never fetches parents")
    void testStandardCoinPreservesVersion() throws Exception {
        Fixture fixture = new Fixture(CoinTicker.LITECOIN, false, 2, INCOMING_NTIME);

        try (MockedStatic<CoinInstance> ignored = staticCoinInstance()) {
            FullHttpResponse response = sign(newHandler(CoinTicker.LITECOIN, fixture),
                    fixture.unsignedWireBytes);

            JsonObject result = envelope(response).getAsJsonObject("result");
            Transaction signed = parseWire(CoinTicker.LITECOIN, fixture.params,
                    result.get("hex").getAsString());
            assertEquals(2, signed.getVersion());
            verify(httpClient, never()).getRawTransaction(any(), anyString(), anyBoolean());
        }
    }

    @Test
    @DisplayName("createrawtransaction uses the coin's BCF version")
    void testCreateRawTransactionUsesConfiguredVersion() throws Exception {
        Fixture fixture = new Fixture(CoinTicker.PKOIN, true, 2, INCOMING_NTIME);
        LegacyAddress destination = LegacyAddress.fromKey(fixture.params, new ECKey());

        JsonObject input = new JsonObject();
        input.addProperty("txid", "11".repeat(32));
        input.addProperty("vout", 0);
        JsonArray inputs = new JsonArray();
        inputs.add(input);

        JsonObject output = new JsonObject();
        output.addProperty("address", destination.toBase58());
        output.addProperty("amount", 0.05);
        JsonArray outputs = new JsonArray();
        outputs.add(output);

        JsonArray params = new JsonArray();
        params.add(inputs);
        params.add(outputs);

        try (MockedStatic<CoinInstance> ignored = staticCoinInstance()) {
            FullHttpResponse response = call(newHandler(CoinTicker.PKOIN, fixture),
                    "createrawtransaction", params);

            String hex = envelope(response).get("result").getAsString();
            Transaction created = HTTPServerHandler.parseForCoin(CoinTicker.PKOIN,
                    fixture.params, bytes(hex));
            assertEquals(2, created.getVersion(),
                    "the transaction version must come from BCF, not bitcoinj's default");
        }
    }

    private FullHttpResponse call(HTTPServerHandler handler, String method, JsonArray params) {
        JsonObject body = new JsonObject();
        body.addProperty("method", method);
        body.add("params", params);

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Unpooled.wrappedBuffer(bytes));
        String credentials = Base64.getEncoder().encodeToString(
                (USER + ":" + PASS).getBytes(StandardCharsets.UTF_8));
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Basic " + credentials);
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);

        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            channel.writeInbound(request);
            channel.flush();
            return channel.readOutbound();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("locktime survives signing")
    void testLockTimeSurvivesSigning() throws Exception {
        Fixture fixture = new Fixture(CoinTicker.LITECOIN, false, 2, INCOMING_NTIME);
        Transaction unsigned = HTTPServerHandler.parseForCoin(CoinTicker.LITECOIN,
                fixture.params, fixture.unsignedWireBytes);
        unsigned.setLockTime(123_456L);
        unsigned.setVersion(2);

        try (MockedStatic<CoinInstance> ignored = staticCoinInstance()) {
            FullHttpResponse response = sign(newHandler(CoinTicker.LITECOIN, fixture),
                    unsigned.bitcoinSerialize());

            JsonObject result = envelope(response).getAsJsonObject("result");
            Transaction signed = parseWire(CoinTicker.LITECOIN, fixture.params,
                    result.get("hex").getAsString());
            assertEquals(123_456L, signed.getLockTime());
        }
    }
}
