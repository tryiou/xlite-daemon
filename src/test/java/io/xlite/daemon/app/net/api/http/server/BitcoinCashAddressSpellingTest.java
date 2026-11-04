package io.xlite.daemon.app.net.api.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.xlite.daemon.app.Version;
import io.xlite.daemon.app.coinconfig.CoinConfig;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.coinconfig.ConfigurableNetworkParameters;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;
import io.xlite.daemon.app.testing.CoinFixture;
import io.xlite.daemon.app.util.ConfigHelper;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.Script;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The address spelling a peer is allowed to send.
 *
 * <p>XBridge carries an address as its 20-byte identifier and rebuilds the string with the coin's
 * own encoder, which on Bitcoin Cash is CashAddr. A wallet that only accepted base58check would
 * refuse a destination the chain considers identical — so every RPC that takes an address must
 * accept both spellings and produce the same bytes for either.
 */
class BitcoinCashAddressSpellingTest {

    private static final String USER = "testuser";
    private static final String PASS = "testpass";
    private static final String TXID = "aa".repeat(32);
    private static final String BCH_LEGACY = "1MymJvqYN16k5cXUKeQtP59VnhgAVLk1Kx";
    private static final String BCH_CASH_ADDR =
            "bitcoincash:qrnpanqz45mmudmvs9mfzjnc64tfpk9ggsurdxwm85";
    private static final String BCH_P2SH_LEGACY = "3CWFddi6m4ndiGyKqzYvsFYagqDLPVMTzC";
    private static final String BCH_P2SH_CASH_ADDR =
            "bitcoincash:ppm2qsznhks23z7629mms6s4cwef74vcwvn0h829pq";

    private final CoinInstance coin = mock(CoinInstance.class);

    @BeforeAll
    static void loadRegistry() {
        Map<String, CoinConfig> cfgs = new LinkedHashMap<>();
        cfgs.put(
                "BCH",
                CoinFixture.coin("BCH", "Bitcoin Cash")
                        .prefixes(0, 5, 128)
                        .createTxMethod("BCH")
                        .txVersion(2)
                        .build());
        CoinConfigRegistry.loadForTest(cfgs);
    }

    @AfterAll
    static void resetRegistry() {
        CoinConfigRegistry.resetForTest();
    }

    private static NetworkParameters params() {
        return ConfigurableNetworkParameters.from(CoinConfigRegistry.get("BCH"));
    }

    private HTTPServerHandler handler() {
        HTTPClient httpClient = mock(HTTPClient.class);
        ConfigHelper configHelper = mock(ConfigHelper.class);
        when(coin.getConfigHelper()).thenReturn(configHelper);
        when(configHelper.getRpcUsername()).thenReturn(USER);
        when(configHelper.getRpcPassword()).thenReturn(PASS);
        when(coin.getTicker()).thenReturn(CoinTicker.BITCOIN_CASH);
        when(coin.getNetworkParameters()).thenReturn(params());
        when(coin.isInstanceRunning()).thenReturn(true);
        return new HTTPServerHandler(coin, httpClient);
    }

    /** {@code createrawtransaction} over one input, paying {@code address}. */
    private JsonObject createRaw(String address) {
        JsonObject input = new JsonObject();
        input.addProperty("txid", TXID);
        input.addProperty("vout", 0);
        input.addProperty("sequence", 4294967295L);
        JsonArray inputs = new JsonArray();
        inputs.add(input);
        JsonObject outputs = new JsonObject();
        outputs.addProperty(address, 0.0001);

        JsonObject body = new JsonObject();
        body.addProperty("method", "createrawtransaction");
        JsonArray params = new JsonArray();
        params.add(inputs);
        params.add(outputs);
        body.add("params", params);
        return call(body);
    }

    private JsonObject call(JsonObject body) {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        FullHttpRequest request =
                new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Unpooled.wrappedBuffer(bytes));
        String credentials =
                Base64.getEncoder()
                        .encodeToString((USER + ":" + PASS).getBytes(StandardCharsets.UTF_8));
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Basic " + credentials);
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);

        EmbeddedChannel channel = new EmbeddedChannel(handler());
        try {
            channel.writeInbound(request);
            channel.flush();
            FullHttpResponse response = channel.readOutbound();
            assertEquals(HttpResponseStatus.OK, response.status());
            return JsonParser.parseString(response.content().toString(CharsetUtil.UTF_8))
                    .getAsJsonObject();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** The output script a {@code createrawtransaction} response pays to. */
    private static String outputScript(JsonObject envelope) {
        assertTrue(envelope.get("error").isJsonNull(), "unexpected error: " + envelope);
        byte[] wire = java.util.HexFormat.of().parseHex(envelope.get("result").getAsString());
        Transaction tx = TxWire.parseForCoin(CoinTicker.BITCOIN_CASH, params(), wire);
        assertEquals(1, tx.getOutputs().size());
        Script script = tx.getOutputs().get(0).getScriptPubKey();
        return java.util.HexFormat.of().formatHex(script.getProgram());
    }

    /**
     * The two spellings of one key produce the identical output script: p2pkh over the same
     * HASH160. A wallet that refused the CashAddr would answer -1006 and cancel the swap.
     */
    @Test
    @DisplayName("createrawtransaction: legacy and CashAddr build the same p2pkh output")
    void createRawTransactionAcceptsBothSpellings() {
        try (MockedStatic<CoinInstance> ignored = mockStatic(CoinInstance.class)) {
            ignored.when(() -> CoinInstance.getInstance(any())).thenReturn(coin);
            ignored.when(CoinInstance::getVersionString).thenReturn(Version.SUBVERSION);

            String legacyScript = outputScript(createRaw(BCH_LEGACY));
            String cashScript = outputScript(createRaw(BCH_CASH_ADDR));

            assertEquals(legacyScript, cashScript);
            assertTrue(legacyScript.startsWith("76a914"), legacyScript);
            assertTrue(legacyScript.endsWith("88ac"), legacyScript);
        }
    }

    /** The same holds for a script-hash destination, which sorts into its own pass. */
    @Test
    @DisplayName("createrawtransaction: legacy and CashAddr build the same p2sh output")
    void createRawTransactionAcceptsBothScriptHashSpellings() {
        try (MockedStatic<CoinInstance> ignored = mockStatic(CoinInstance.class)) {
            ignored.when(() -> CoinInstance.getInstance(any())).thenReturn(coin);
            ignored.when(CoinInstance::getVersionString).thenReturn(Version.SUBVERSION);

            String legacyScript = outputScript(createRaw(BCH_P2SH_LEGACY));
            String cashScript = outputScript(createRaw(BCH_P2SH_CASH_ADDR));

            assertEquals(legacyScript, cashScript);
            assertTrue(legacyScript.startsWith("a914"), legacyScript);
            assertTrue(legacyScript.endsWith("87"), legacyScript);
        }
    }

    /** An address for another coin is still refused, in either spelling. */
    @Test
    @DisplayName("createrawtransaction: a foreign address is refused")
    void createRawTransactionRejectsForeignAddress() {
        try (MockedStatic<CoinInstance> ignored = mockStatic(CoinInstance.class)) {
            ignored.when(() -> CoinInstance.getInstance(any())).thenReturn(coin);
            ignored.when(CoinInstance::getVersionString).thenReturn(Version.SUBVERSION);

            JsonObject envelope = createRaw("LVVsQBXQ4Px8edfuYMQozshSGeabHK1wRt");
            assertTrue(envelope.get("result").isJsonNull());
            assertEquals(-1006, envelope.getAsJsonObject("error").get("code").getAsInt());
        }
    }

    /** {@code validateaddress} reports both spellings of a key as valid. */
    @Test
    @DisplayName("validateaddress: both spellings are valid for the coin")
    void validateAddressAcceptsBothSpellings() {
        try (MockedStatic<CoinInstance> ignored = mockStatic(CoinInstance.class)) {
            ignored.when(() -> CoinInstance.getInstance(any())).thenReturn(coin);
            ignored.when(CoinInstance::getVersionString).thenReturn(Version.SUBVERSION);
            ConfigurableNetworkParameters bch = (ConfigurableNetworkParameters) params();
            when(coin.parseAddress(any()))
                    .thenAnswer(invocation ->
                            io.xlite.daemon.app.util.Utility.parseAddress(
                                    bch, "BCH", invocation.getArgument(0)));

            for (String spelling : new String[] {BCH_LEGACY, BCH_CASH_ADDR}) {
                JsonObject body = new JsonObject();
                body.addProperty("method", "validateaddress");
                JsonArray params = new JsonArray();
                params.add(spelling);
                body.add("params", params);

                JsonObject envelope = call(body);
                assertTrue(
                        envelope.getAsJsonObject("result").get("isvalid").getAsBoolean(),
                        spelling + " must be valid");
            }
        }
    }
}
