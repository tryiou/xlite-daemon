package io.xlite.daemon.app.net.api.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
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
 * {@code validateaddress} must describe the script an address pays to.
 *
 * <p>A caller that only gets {@code isvalid} back cannot build an output or compare an address with
 * the chain, and every non-P2SH address used to answer an empty {@code scriptPubKey}. The scripts
 * here are the ones each chain's own {@code validateaddress} returns for the same keys.
 */
class ValidateAddressScriptTest {

    private static final String USER = "testuser";
    private static final String PASS = "testpass";

    /** BCH legacy and its CashAddr twin, both naming HASH160 abea45e4…641b. */
    private static final String P2PKH_ADDRESS = "1Gg1CNtmS4HkmLuBubNvXimo7qZx7RxXpW";

    private static final String P2PKH_CASH_ADDR =
            "bitcoincash:qz47530y2xd65sa3kf5r2kxglj3vj7ryrv5zenk3xu";

    private static final String P2PKH_SCRIPT = "76a914abea45e4519baa43b1b2683558c8fca2c978641b88ac";

    private static final String P2SH_ADDRESS = "3CWFddi6m4ndiGyKqzYvsFYagqDLPVMTzC";

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
        ConfigHelper configHelper = mock(ConfigHelper.class);
        when(coin.getConfigHelper()).thenReturn(configHelper);
        when(configHelper.getRpcUsername()).thenReturn(USER);
        when(configHelper.getRpcPassword()).thenReturn(PASS);
        when(coin.getTicker()).thenReturn(CoinTicker.BITCOIN_CASH);
        when(coin.getNetworkParameters()).thenReturn(params());
        when(coin.isInstanceRunning()).thenReturn(true);
        return new HTTPServerHandler(coin, mock(HTTPClient.class));
    }

    private JsonObject validate(String address) {
        JsonObject body = new JsonObject();
        body.addProperty("method", "validateaddress");
        JsonArray params = new JsonArray();
        params.add(address);
        body.add("params", params);

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
            JsonObject envelope =
                    JsonParser.parseString(response.content().toString(CharsetUtil.UTF_8))
                            .getAsJsonObject();
            assertTrue(envelope.get("error").isJsonNull(), "unexpected error: " + envelope);
            return envelope.get("result").getAsJsonObject();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("a P2PKH address answers the script its chain builds for it")
    void p2pkhAnswersItsOutputScript() {
        LegacyAddress parsed = LegacyAddress.fromBase58(params(), P2PKH_ADDRESS);
        when(coin.parseAddress(P2PKH_ADDRESS)).thenReturn(parsed);
        try (MockedStatic<CoinInstance> ignored = mockStatic(CoinInstance.class)) {
            ignored.when(() -> CoinInstance.getInstance(any())).thenReturn(coin);
            ignored.when(CoinInstance::getVersionString).thenReturn(Version.SUBVERSION);

            JsonObject result = validate(P2PKH_ADDRESS);

            assertEquals(P2PKH_SCRIPT, result.get("scriptPubKey").getAsString());
            assertFalse(result.get("isscript").getAsBoolean());
            assertTrue(result.get("isvalid").getAsBoolean());
        }
    }

    @Test
    @DisplayName("a P2SH address answers its script-hash script")
    void p2shAnswersItsScriptHashScript() {
        when(coin.parseAddress(P2SH_ADDRESS))
                .thenReturn(LegacyAddress.fromBase58(params(), P2SH_ADDRESS));
        try (MockedStatic<CoinInstance> ignored = mockStatic(CoinInstance.class)) {
            ignored.when(() -> CoinInstance.getInstance(any())).thenReturn(coin);
            ignored.when(CoinInstance::getVersionString).thenReturn(Version.SUBVERSION);

            JsonObject result = validate(P2SH_ADDRESS);

            assertTrue(result.get("scriptPubKey").getAsString().startsWith("a914"));
            assertTrue(result.get("scriptPubKey").getAsString().endsWith("87"));
            assertTrue(result.get("isscript").getAsBoolean());
        }
    }

    @Test
    @DisplayName("a CashAddr answers the same script as its legacy spelling")
    void cashAddrAnswersTheLegacyScript() {
        when(coin.parseAddress(P2PKH_CASH_ADDR))
                .thenReturn(LegacyAddress.fromBase58(params(), P2PKH_ADDRESS));
        try (MockedStatic<CoinInstance> ignored = mockStatic(CoinInstance.class)) {
            ignored.when(() -> CoinInstance.getInstance(any())).thenReturn(coin);
            ignored.when(CoinInstance::getVersionString).thenReturn(Version.SUBVERSION);

            assertEquals(P2PKH_SCRIPT, validate(P2PKH_CASH_ADDR).get("scriptPubKey").getAsString());
        }
    }

    /**
     * No served coin sets a segwit HRP, so a bech32 address is not one of this coin's addresses and
     * the wallet must say so rather than answer a script for it.
     */
    @Test
    @DisplayName("a bech32 address is refused, because no served coin has a segwit HRP")
    void bech32IsRefused() {
        String bech32 = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
        when(coin.parseAddress(bech32)).thenReturn(null);
        try (MockedStatic<CoinInstance> ignored = mockStatic(CoinInstance.class)) {
            ignored.when(() -> CoinInstance.getInstance(any())).thenReturn(coin);
            ignored.when(CoinInstance::getVersionString).thenReturn(Version.SUBVERSION);

            JsonObject result = validate(bech32);

            assertFalse(result.get("isvalid").getAsBoolean());
            assertEquals("", result.get("scriptPubKey").getAsString());
        }
    }

    @Test
    @DisplayName("an unparseable address is invalid and has no script")
    void invalidAddressAnswersNothing() {
        when(coin.parseAddress("not-an-address")).thenReturn(null);
        try (MockedStatic<CoinInstance> ignored = mockStatic(CoinInstance.class)) {
            ignored.when(() -> CoinInstance.getInstance(any())).thenReturn(coin);
            ignored.when(CoinInstance::getVersionString).thenReturn(Version.SUBVERSION);

            JsonObject result = validate("not-an-address");

            assertFalse(result.get("isvalid").getAsBoolean());
            assertEquals("", result.get("scriptPubKey").getAsString());
            assertFalse(result.get("isscript").getAsBoolean());
        }
    }
}
