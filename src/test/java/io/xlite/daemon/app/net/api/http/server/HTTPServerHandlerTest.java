package io.xlite.daemon.app.net.api.http.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Contract tests for the per-coin JSON-RPC server's malformed-input handling.
 *
 * <p>Part 1 pins exact codes for the known incoherences. Part 2 is a mutation engine: every listed
 * method is hammered with systematically generated malformed shapes, and every response must
 * satisfy the universal envelope invariant (proper JSON, both keys present, exactly one null, error
 * carries int code + string message) instead of a drop or empty body. Upstream is mocked out, so no
 * mutation can touch the network.
 */
class HTTPServerHandlerTest {

    private static final String USER = "testuser";
    private static final String PASS = "testpass";
    private static final String TXID = "00".repeat(32);

    private HTTPServerHandler newHandler() {
        return newHandler(true);
    }

    private HTTPServerHandler newHandler(boolean providerServing) {
        // Fresh mocks per handler: reloadconfig schedules a delayed call on the
        // coin, and a shared mock would let that thread race the next stub.
        CoinInstance coin = mock(CoinInstance.class);
        HTTPClient httpClient = mock(HTTPClient.class);
        ConfigHelper configHelper = mock(ConfigHelper.class);
        org.mockito.Mockito.when(coin.getConfigHelper()).thenReturn(configHelper);
        org.mockito.Mockito.when(configHelper.getRpcUsername()).thenReturn(USER);
        org.mockito.Mockito.when(configHelper.getRpcPassword()).thenReturn(PASS);
        org.mockito.Mockito.when(coin.getTicker()).thenReturn(CoinTicker.BLOCKNET);
        org.mockito.Mockito.when(coin.isInstanceRunning()).thenReturn(providerServing);
        HTTPServerHandler handler = new HTTPServerHandler(coin, httpClient);
        JsonObject txError = new JsonObject();
        JsonObject txErrorDetail = new JsonObject();
        txErrorDetail.addProperty("code", -5);
        txError.add("error", txErrorDetail);
        org.mockito.Mockito.when(httpClient.getTransaction(any(), anyString(), anyBoolean()))
                .thenReturn(txError);
        return handler;
    }

    private FullHttpResponse post(String body) {
        return post(HttpMethod.POST, "/", body);
    }

    private FullHttpResponse post(HttpMethod method, String uri, String body) {
        return post(newHandler(), method, uri, body);
    }

    private FullHttpResponse post(
            HTTPServerHandler handler, HttpMethod method, String uri, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        FullHttpRequest request =
                new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, method, uri, Unpooled.wrappedBuffer(bytes));
        String credentials =
                Base64.getEncoder()
                        .encodeToString((USER + ":" + PASS).getBytes(StandardCharsets.UTF_8));
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

    private static JsonObject envelopeOf(FullHttpResponse response) {
        String content = response.content().toString(CharsetUtil.UTF_8);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    private static int errorCode(FullHttpResponse response) {
        return envelopeOf(response).getAsJsonObject("error").get("code").getAsInt();
    }

    private static void assertWellFormedEnvelope(FullHttpResponse response, String body) {
        assertNotNull(response, "dropped connection for: " + body);
        assertEquals(
                "application/json",
                response.headers().get(HttpHeaderNames.CONTENT_TYPE),
                "missing content type for: " + body);
        String content = response.content().toString(CharsetUtil.UTF_8);
        assertTrue(content != null && !content.isEmpty(), "empty body for: " + body);
        JsonObject envelope = JsonParser.parseString(content).getAsJsonObject();
        assertTrue(envelope.has("result") && envelope.has("error"), "envelope shape for: " + body);
        boolean resultNull = envelope.get("result").isJsonNull();
        boolean errorNull = envelope.get("error").isJsonNull();
        assertTrue(
                resultNull != errorNull, "exactly one of result/error must be null for: " + body);
        if (!errorNull) {
            JsonObject error = envelope.getAsJsonObject("error");
            assertTrue(
                    error.has("code") && error.get("code").isJsonPrimitive(),
                    "error code for: " + body);
            assertTrue(
                    error.has("message") && error.get("message").isJsonPrimitive(),
                    "error message for: " + body);
        }
    }

    @Test
    void aLocalMethodAnswersWhileTheProviderIsDown() {
        FullHttpResponse response =
                post(
                        newHandler(false),
                        HttpMethod.POST,
                        "/",
                        "{\"method\":\"historystatus\",\"params\":[]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        JsonObject result = envelopeOf(response).getAsJsonObject("result");
        assertFalse(result.get("native").getAsBoolean());
    }

    @Test
    void aChainDataMethodStaysUnavailableWhileTheProviderIsDown() {
        FullHttpResponse response =
                post(
                        newHandler(false),
                        HttpMethod.POST,
                        "/",
                        "{\"method\":\"listunspent\",\"params\":[]}");
        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
        assertEquals(-1112, errorCode(response));
    }

    @Test
    void anUnknownMethodIsNotFoundRatherThanUnavailable() {
        FullHttpResponse response =
                post(
                        newHandler(false),
                        HttpMethod.POST,
                        "/",
                        "{\"method\":\"nosuchmethod\",\"params\":[]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-32601, errorCode(response));
    }

    @Test
    void gettxoutDoesNotNestAnUpstreamEnvelope() {
        FullHttpResponse response =
                post("{\"method\":\"gettxout\",\"params\":[\"" + TXID + "\",0]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        JsonObject envelope = envelopeOf(response);
        assertTrue(envelope.get("result").isJsonNull(), "the upstream failure leaked into result");
        assertEquals(-5, errorCode(response));
    }

    @Test
    void testBadHexDecodeReturns1007Envelope() {
        FullHttpResponse response =
                post("{\"method\":\"decoderawtransaction\",\"params\":[\"zz\"]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-1007, errorCode(response));
    }

    @Test
    void testGetBlockHashStringHeightReturnsMinusOneEnvelope() {
        FullHttpResponse response = post("{\"method\":\"getblockhash\",\"params\":[\"abc\"]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-1, errorCode(response));
    }

    @Test
    void testGetTxOutSwappedTypesReturnsMinusOneEnvelope() {
        FullHttpResponse response =
                post("{\"method\":\"gettxout\",\"params\":[0,\"" + TXID + "\"]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-1, errorCode(response));
    }

    @Test
    void testCreateRawTransactionStringParamsReturnsMinusOneEnvelope() {
        FullHttpResponse response =
                post("{\"method\":\"createrawtransaction\",\"params\":[\"[{}]\",\"{}\"]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-1, errorCode(response));
    }

    @Test
    void testMissingParamsTreatedAsEmpty() {
        FullHttpResponse response = post("{\"method\":\"ping\"}");
        assertEquals(HttpResponseStatus.OK, response.status());
        JsonObject envelope = envelopeOf(response);
        assertEquals(1, envelope.get("result").getAsInt());
        assertTrue(envelope.get("error").isJsonNull());
    }

    @Test
    void testNullParamsTreatedAsEmpty() {
        FullHttpResponse response = post("{\"method\":\"ping\",\"params\":null}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(1, envelopeOf(response).get("result").getAsInt());
    }

    @Test
    void testNonArrayParamsReturns1001Envelope() {
        FullHttpResponse response = post("{\"method\":\"ping\",\"params\":\"foo\"}");
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertEquals(-1001, errorCode(response));
    }

    @Test
    void testMissingMethodReturns1001Envelope() {
        FullHttpResponse response = post("{\"params\":[]}");
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertEquals(-1001, errorCode(response));
    }

    @Test
    void testUnknownMethodReturns32601Envelope() {
        FullHttpResponse response = post("{\"method\":\"nosuchmethod\",\"params\":[]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-32601, errorCode(response));
    }

    /**
     * The help text is the only discovery surface a client has, so it must list exactly the methods
     * the dispatcher serves. A method added without a help line (or a stale line for a removed one)
     * is invisible or misleading to every consumer.
     */
    @Test
    void helpListsEveryRegisteredMethod() {
        FullHttpResponse response = post("{\"method\":\"help\",\"params\":[]}");
        String text = envelopeOf(response).get("result").getAsString();
        Set<String> listed = new TreeSet<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("=====")) {
                continue;
            }
            // The preamble names the server, not a method; only a lowercase
            // first token is a method line.
            String name = trimmed.split("[ <]", 2)[0];
            if (name.matches("[a-z]+")) {
                listed.add(name);
            }
        }
        assertEquals(
                new TreeSet<>(
                        List.of(
                                "help",
                                "version",
                                "ping",
                                "reloadconfig",
                                "getinfo",
                                "getblockcount",
                                "getnetworkinfo",
                                "getfees",
                                "getblockchaininfo",
                                "getrawmempool",
                                "getblockhash",
                                "getblock",
                                "listunspent",
                                "listtransactions",
                                "historystatus",
                                "getaddressesbyaccount",
                                "getnewaddress",
                                "importprivkey",
                                "dumpprivkey",
                                "validateaddress",
                                "gettransaction",
                                "getrawtransaction",
                                "gettxout",
                                "createrawtransaction",
                                "decoderawtransaction",
                                "signrawtransaction",
                                "sendrawtransaction",
                                "sendtransaction",
                                "signmessage",
                                "verifymessage")),
                listed);
    }

    @Test
    void testInvalidJsonReturns1001Envelope() {
        FullHttpResponse response = post("this is not json");
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertEquals(-1001, errorCode(response));
    }

    /**
     * A rejected body is never written to the log: a malformed importprivkey call carries a private
     * key, and the body must not survive in a log file just because it failed to parse.
     */
    @Test
    void testRejectedBodyIsNeverLogged() {
        String secret = "cVHiddenKeyMaterial";
        String body = "{\"method\":\"importprivkey\",\"params\":[\"" + secret + "\"]";
        List<LogRecord> records =
                captureLogs(() -> {
                    FullHttpResponse response = post(body);
                    assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
                });
        assertFalse(records.isEmpty(), "no log record was captured");
        assertFalse(
                records.stream()
                        .map(LogRecord::getMessage)
                        .anyMatch(message -> message != null && message.contains(secret)),
                "the rejected request body reached the log");
    }

    /** Runs an action with the global logger capturing every level. */
    private static List<LogRecord> captureLogs(Runnable action) {
        Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        Level previous = logger.getLevel();
        List<LogRecord> records = new ArrayList<>();
        Handler handler =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        records.add(record);
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
        return records;
    }

    @Test
    void testValidateAddressObjectParamReturnsMinusOneEnvelope() {
        FullHttpResponse response = post("{\"method\":\"validateaddress\",\"params\":[{}]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-1, errorCode(response));
    }

    @Test
    void testGetNewAddressExtraParamsReturnsMinusOneEnvelope() {
        FullHttpResponse response = post("{\"method\":\"getnewaddress\",\"params\":[\"x\",\"y\"]}");
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(-1, errorCode(response));
    }

    private static final Map<String, String> BASE_PARAMS =
            Map.ofEntries(
                    Map.entry("ping", "[]"),
                    Map.entry("help", "[]"),
                    Map.entry("version", "[]"),
                    Map.entry("getinfo", "[]"),
                    Map.entry("getnewaddress", "[]"),
                    Map.entry("getnetworkinfo", "[]"),
                    Map.entry("getblockchaininfo", "[]"),
                    Map.entry("getfees", "[]"),
                    Map.entry("getrawmempool", "[]"),
                    Map.entry("getblockcount", "[]"),
                    Map.entry("getaddressesbyaccount", "[]"),
                    Map.entry("listunspent", "[]"),
                    Map.entry("listtransactions", "[]"),
                    Map.entry("reloadconfig", "[]"),
                    Map.entry("validateaddress", "[\"D8r8y7dummy\"]"),
                    Map.entry("decoderawtransaction", "[\"zz\"]"),
                    Map.entry("createrawtransaction", "[\"[{}]\",\"{}\"]"),
                    Map.entry("signrawtransaction", "[\"zz\"]"),
                    Map.entry("sendrawtransaction", "[\"zz\"]"),
                    Map.entry("getblockhash", "[1]"),
                    Map.entry("getblock", "[\"" + TXID + "\"]"),
                    Map.entry("getrawtransaction", "[\"" + TXID + "\"]"),
                    Map.entry("gettransaction", "[\"" + TXID + "\"]"),
                    Map.entry("gettxout", "[\"" + TXID + "\",0]"),
                    Map.entry("signmessage", "[\"a\",\"b\"]"),
                    Map.entry("verifymessage", "[\"a\",\"b\",\"c\"]"),
                    Map.entry("dumpprivkey", "[\"a\"]"),
                    Map.entry("importprivkey", "[\"wif\"]"),
                    Map.entry("sendtransaction", "[\"addr\",0.001,true]"));

    private static final List<String> ENVELOPE_MUTANTS =
            List.of(
                    "{\"method\":\"ping\"}",
                    "{\"method\":\"ping\",\"params\":null}",
                    "{\"method\":\"ping\",\"params\":\"foo\"}",
                    "{\"method\":\"ping\",\"params\":123}",
                    "{\"method\":\"ping\",\"params\":{}}",
                    "{\"params\":[]}",
                    "{\"method\":123,\"params\":[]}",
                    "{\"method\":null,\"params\":[]}",
                    "{\"method\":{},\"params\":[]}",
                    "{\"method\":[],\"params\":[]}",
                    "{\"method\":true,\"params\":[]}",
                    "this is not json",
                    "",
                    "{\"method\":\"nosuchmethod\",\"params\":[]}");

    private static final List<String> REPLACEMENTS =
            List.of("0", "\"x\"", "true", "null", "{}", "[]");

    /**
     * Mutation engine: every method below is exercised with systematically generated malformed
     * shapes. No per-case expectations are hardcoded — each response must satisfy the universal
     * envelope invariant.
     */
    @Test
    void testMutationEngineEveryCallYieldsWellFormedEnvelope() {
        for (String body : mutants()) {
            FullHttpResponse response = post(body);
            assertWellFormedEnvelope(response, body);
            assertTrue(
                    response.status() == HttpResponseStatus.OK
                            || response.status() == HttpResponseStatus.BAD_REQUEST,
                    "unexpected status " + response.status() + " for: " + body);
        }
        assertWellFormedEnvelope(
                post(HttpMethod.GET, "/", "{\"method\":\"ping\",\"params\":[]}"), "GET /");
        assertWellFormedEnvelope(
                post(HttpMethod.POST, "/badpath", "{\"method\":\"ping\",\"params\":[]}"),
                "POST /badpath");
    }

    private static List<String> mutants() {
        List<String> mutants = new ArrayList<>(ENVELOPE_MUTANTS);
        for (Map.Entry<String, String> base : BASE_PARAMS.entrySet()) {
            addMethodMutants(mutants, base.getKey(), base.getValue());
        }
        mutants.add("{\"method\":\"ping\",\"params\":[]}x");
        return mutants;
    }

    /** The base call, no params, extra params, every prefix, and every replaced param. */
    private static void addMethodMutants(List<String> mutants, String method, String baseJson) {
        JsonArray base = JsonParser.parseString(baseJson).getAsJsonArray();
        mutants.add(call(method, base));
        mutants.add(call(method, new JsonArray()));
        mutants.add(call(method, withExtraParams(base)));
        for (int size = 1; size < base.size(); size++) {
            mutants.add(call(method, prefix(base, size)));
        }
        for (int index = 0; index < base.size(); index++) {
            for (String replacement : REPLACEMENTS) {
                mutants.add(call(method, replaced(base, index, replacement)));
            }
        }
    }

    private static String call(String method, JsonArray params) {
        return "{\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private static JsonArray withExtraParams(JsonArray base) {
        JsonArray over = base.deepCopy();
        over.add("extra-junk");
        over.add(999);
        return over;
    }

    private static JsonArray prefix(JsonArray base, int size) {
        JsonArray prefix = new JsonArray();
        for (int index = 0; index < size; index++) {
            prefix.add(base.get(index).deepCopy());
        }
        return prefix;
    }

    private static JsonArray replaced(JsonArray base, int index, String replacement) {
        JsonArray mutated = base.deepCopy();
        mutated.set(index, JsonParser.parseString(replacement));
        return mutated;
    }
}
