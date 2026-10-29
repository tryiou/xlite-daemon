package io.xlite.daemon.app.net.api.http.server;

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
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.api.http.client.HTTPClient;
import io.xlite.daemon.app.util.ConfigHelper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import static org.mockito.Mockito.mock;

/**
 * Contract tests for the per-coin JSON-RPC server's malformed-input handling.
 *
 * <p>Part 1 pins exact codes for the known incoherences. Part 2 is a
 * mutation engine: every listed method is hammered with systematically
 * generated malformed shapes, and every response must satisfy the universal
 * envelope invariant (proper JSON, both keys present, exactly one null,
 * error carries int code + string message) instead of a drop or empty body.
 * Upstream is mocked out, so no mutation can touch the network.
 */
class HTTPServerHandlerMalformedInputTest {

    private static final String USER = "testuser";
    private static final String PASS = "testpass";
    private static final String TXID = "00".repeat(32);

    private final CoinInstance coin = mock(CoinInstance.class);
    private final HTTPClient httpClient = mock(HTTPClient.class);

    private HTTPServerHandler newHandler() throws Exception {
        ConfigHelper configHelper = mock(ConfigHelper.class);
        org.mockito.Mockito.when(coin.getConfigHelper()).thenReturn(configHelper);
        org.mockito.Mockito.when(configHelper.getRpcUsername()).thenReturn(USER);
        org.mockito.Mockito.when(configHelper.getRpcPassword()).thenReturn(PASS);
        org.mockito.Mockito.when(coin.getTicker()).thenReturn(CoinTicker.BLOCKNET);
        org.mockito.Mockito.when(coin.isInstanceRunning()).thenReturn(true);
        HTTPServerHandler handler = new HTTPServerHandler(coin);
        Field field = HTTPServerHandler.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(handler, httpClient);
        JsonObject txError = new JsonObject();
        JsonObject txErrorDetail = new JsonObject();
        txErrorDetail.addProperty("code", -5);
        txError.add("error", txErrorDetail);
        org.mockito.Mockito.when(httpClient.getTransaction(any(), anyString(), anyBoolean()))
                .thenReturn(txError);
        return handler;
    }

    private FullHttpResponse post(String body) throws Exception {
        return post(HttpMethod.POST, "/", body);
    }

    private FullHttpResponse post(HttpMethod method, String uri, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, method, uri,
                Unpooled.wrappedBuffer(bytes));
        String credentials = Base64.getEncoder().encodeToString(
                (USER + ":" + PASS).getBytes(StandardCharsets.UTF_8));
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Basic " + credentials);
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        EmbeddedChannel channel = new EmbeddedChannel(newHandler());
        try {
            channel.writeInbound(request);
            channel.flush();
            return channel.readOutbound();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static JsonObject envelopeOf(FullHttpResponse response) {
        String content = response.content().toString(CharsetUtil.UTF_8);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    private static int errorCode(FullHttpResponse response) {
        return envelopeOf(response).getAsJsonObject("error").get("code").getAsInt();
    }

    private static void assertWellFormedEnvelope(FullHttpResponse response, String body) {
        assertNotNull(response, "dropped connection for: " + body);
        assertEquals("application/json",
                response.headers().get(HttpHeaderNames.CONTENT_TYPE),
                "missing content type for: " + body);
        String content = response.content().toString(CharsetUtil.UTF_8);
        assertTrue(content != null && !content.isEmpty(), "empty body for: " + body);
        JsonObject envelope = JsonParser.parseString(content).getAsJsonObject();
        assertTrue(envelope.has("result") && envelope.has("error"),
                "envelope shape for: " + body);
        boolean resultNull = envelope.get("result").isJsonNull();
        boolean errorNull = envelope.get("error").isJsonNull();
        assertTrue(resultNull != errorNull,
                "exactly one of result/error must be null for: " + body);
        if (!errorNull) {
            JsonObject error = envelope.getAsJsonObject("error");
            assertTrue(error.has("code") && error.get("code").isJsonPrimitive(),
                    "error code for: " + body);
            assertTrue(error.has("message") && error.get("message").isJsonPrimitive(),
                    "error message for: " + body);
        }
    }

    @Test
    void testBadHexDecodeReturns1007Envelope() throws Exception {
        FullHttpResponse response = post(
                "{\"method\":\"decoderawtransaction\",\"params\":[\"zz\"]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-1007, errorCode(response));
    }

    @Test
    void testGetBlockHashStringHeightReturnsMinusOneEnvelope() throws Exception {
        FullHttpResponse response = post(
                "{\"method\":\"getblockhash\",\"params\":[\"abc\"]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-1, errorCode(response));
    }

    @Test
    void testGetTxOutSwappedTypesReturnsMinusOneEnvelope() throws Exception {
        FullHttpResponse response = post(
                "{\"method\":\"gettxout\",\"params\":[0,\"" + TXID + "\"]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-1, errorCode(response));
    }

    @Test
    void testCreateRawTransactionStringParamsReturnsMinusOneEnvelope() throws Exception {
        FullHttpResponse response = post(
                "{\"method\":\"createrawtransaction\",\"params\":[\"[{}]\",\"{}\"]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-1, errorCode(response));
    }

    @Test
    void testMissingParamsTreatedAsEmpty() throws Exception {
        FullHttpResponse response = post("{\"method\":\"ping\"}");
        assertEquals(HttpResponseStatus.OK, response.status());
        JsonObject envelope = envelopeOf(response);
        assertEquals(1, envelope.get("result").getAsInt());
        assertTrue(envelope.get("error").isJsonNull());
    }

    @Test
    void testNullParamsTreatedAsEmpty() throws Exception {
        FullHttpResponse response = post("{\"method\":\"ping\",\"params\":null}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(1, envelopeOf(response).get("result").getAsInt());
    }

    @Test
    void testNonArrayParamsReturns1001Envelope() throws Exception {
        FullHttpResponse response = post("{\"method\":\"ping\",\"params\":\"foo\"}");
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertEquals(-1001, errorCode(response));
    }

    @Test
    void testMissingMethodReturns1001Envelope() throws Exception {
        FullHttpResponse response = post("{\"params\":[]}");
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertEquals(-1001, errorCode(response));
    }

    @Test
    void testUnknownMethodReturns32601Envelope() throws Exception {
        FullHttpResponse response = post("{\"method\":\"nosuchmethod\",\"params\":[]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-32601, errorCode(response));
    }

    @Test
    void testInvalidJsonReturns1001Envelope() throws Exception {
        FullHttpResponse response = post("this is not json");
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertEquals(-1001, errorCode(response));
    }

    @Test
    void testValidateAddressObjectParamReturnsMinusOneEnvelope() throws Exception {
        FullHttpResponse response = post(
                "{\"method\":\"validateaddress\",\"params\":[{}]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-1, errorCode(response));
    }

    @Test
    void testGetNewAddressExtraParamsReturnsMinusOneEnvelope() throws Exception {
        FullHttpResponse response = post(
                "{\"method\":\"getnewaddress\",\"params\":[\"x\",\"y\"]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-1, errorCode(response));
    }

    /**
     * Mutation engine: every method below is exercised with systematically
     * generated malformed shapes. No per-case expectations are hardcoded —
     * each response must satisfy the universal envelope invariant.
     */
    @Test
    void testMutationEngineEveryCallYieldsWellFormedEnvelope() throws Exception {
        Map<String, String> baseParams = new LinkedHashMap<>();
        baseParams.put("ping", "[]");
        baseParams.put("help", "[]");
        baseParams.put("version", "[]");
        baseParams.put("getinfo", "[]");
        baseParams.put("getnewaddress", "[]");
        baseParams.put("getnetworkinfo", "[]");
        baseParams.put("getblockchaininfo", "[]");
        baseParams.put("getfees", "[]");
        baseParams.put("getrawmempool", "[]");
        baseParams.put("getblockcount", "[]");
        baseParams.put("getaddressesbyaccount", "[]");
        baseParams.put("listunspent", "[]");
        baseParams.put("listtransactions", "[]");
        baseParams.put("reloadconfig", "[]");
        baseParams.put("validateaddress", "[\"D8r8y7dummy\"]");
        baseParams.put("decoderawtransaction", "[\"zz\"]");
        baseParams.put("createrawtransaction", "[\"[{}]\",\"{}\"]");
        baseParams.put("signrawtransaction", "[\"zz\"]");
        baseParams.put("sendrawtransaction", "[\"zz\"]");
        baseParams.put("getblockhash", "[1]");
        baseParams.put("getblock", "[\"" + TXID + "\"]");
        baseParams.put("getrawtransaction", "[\"" + TXID + "\"]");
        baseParams.put("gettransaction", "[\"" + TXID + "\"]");
        baseParams.put("gettxout", "[\"" + TXID + "\",0]");
        baseParams.put("signmessage", "[\"a\",\"b\"]");
        baseParams.put("verifymessage", "[\"a\",\"b\",\"c\"]");
        baseParams.put("dumpprivkey", "[\"a\"]");
        baseParams.put("importprivkey", "[\"wif\"]");
        baseParams.put("sendtransaction", "[\"addr\",0.001,true]");

        List<String> mutants = new ArrayList<>();
        mutants.add("{\"method\":\"ping\"}");
        mutants.add("{\"method\":\"ping\",\"params\":null}");
        mutants.add("{\"method\":\"ping\",\"params\":\"foo\"}");
        mutants.add("{\"method\":\"ping\",\"params\":123}");
        mutants.add("{\"method\":\"ping\",\"params\":{}}");
        mutants.add("{\"params\":[]}");
        mutants.add("{\"method\":123,\"params\":[]}");
        mutants.add("{\"method\":null,\"params\":[]}");
        mutants.add("{\"method\":{},\"params\":[]}");
        mutants.add("{\"method\":[],\"params\":[]}");
        mutants.add("{\"method\":true,\"params\":[]}");
        mutants.add("this is not json");
        mutants.add("");
        mutants.add("{\"method\":\"nosuchmethod\",\"params\":[]}");

        for (Map.Entry<String, String> entry : baseParams.entrySet()) {
            String method = entry.getKey();
            JsonArray base = JsonParser.parseString(entry.getValue()).getAsJsonArray();
            mutants.add("{\"method\":\"" + method + "\",\"params\":" + base + "}");
            mutants.add("{\"method\":\"" + method + "\",\"params\":[]}");
            JsonArray over = base.deepCopy();
            over.add("extra-junk");
            over.add(999);
            mutants.add("{\"method\":\"" + method + "\",\"params\":" + over + "}");
            for (int k = 1; k < base.size(); k++) {
                JsonArray prefix = new JsonArray();
                for (int j = 0; j < k; j++) {
                    prefix.add(base.get(j).deepCopy());
                }
                mutants.add("{\"method\":\"" + method + "\",\"params\":" + prefix + "}");
            }
            for (int i = 0; i < base.size(); i++) {
                for (String replacement : new String[]{"0", "\"x\"", "true", "null", "{}", "[]"}) {
                    JsonArray mutated = base.deepCopy();
                    mutated.set(i, JsonParser.parseString(replacement));
                    mutants.add("{\"method\":\"" + method + "\",\"params\":" + mutated + "}");
                }
            }
        }
        mutants.add("{\"method\":\"ping\",\"params\":[]}x");

        for (String body : mutants) {
            FullHttpResponse response = post(body);
            assertWellFormedEnvelope(response, body);
            assertTrue(response.status() == HttpResponseStatus.OK
                            || response.status() == HttpResponseStatus.BAD_REQUEST,
                    "unexpected status " + response.status() + " for: " + body);
        }

        assertWellFormedEnvelope(post(HttpMethod.GET, "/", "{\"method\":\"ping\",\"params\":[]}"),
                "GET /");
        assertWellFormedEnvelope(post(HttpMethod.POST, "/badpath", "{\"method\":\"ping\",\"params\":[]}"),
                "POST /badpath");
    }
}
