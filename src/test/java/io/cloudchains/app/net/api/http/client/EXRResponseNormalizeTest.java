package io.cloudchains.app.net.api.http.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.cloudchains.app.net.CoinTicker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class EXRResponseNormalizeTest {

    // --- normalizeEXRResponse ---

    @Test
    public void testNormalize_EnvelopePassthrough() {
        String body = "{\"result\":{\"txid\":\"aa\",\"confirmations\":3},\"error\":null}";
        JsonElement parsed = JsonParser.parseString(body);
        assertEquals(body, HTTPClient.normalizeEXRResponse(parsed));
    }

    @Test
    public void testNormalize_BareUtxosObjectPassthrough() {
        String body = "{\"utxos\":[{\"txhash\":\"ab\",\"vout\":1,\"value\":1.5,\"address\":\"X\",\"block_number\":100}]}";
        JsonElement parsed = JsonParser.parseString(body);
        assertEquals(body, HTTPClient.normalizeEXRResponse(parsed));
    }

    @Test
    public void testNormalize_BareHistoryArrayPassthrough() {
        String body = "[[{\"address\":\"X\",\"txid\":\"t1\",\"amount\":0.5}]]";
        JsonElement parsed = JsonParser.parseString(body);
        assertEquals(body, HTTPClient.normalizeEXRResponse(parsed));
    }

    @Test
    public void testNormalize_StringWrappedObjectResultUnwrappedOnce() {
        JsonObject obj = new JsonObject();
        obj.addProperty("result", "{\"utxos\":[]}");
        assertEquals("{\"utxos\":[]}", HTTPClient.normalizeEXRResponse(obj));
    }

    @Test
    public void testNormalize_StringWrappedArrayResultUnwrappedOnce() {
        JsonObject obj = new JsonObject();
        obj.addProperty("result", "[[{\"address\":\"X\"}]]");
        assertEquals("[[{\"address\":\"X\"}]]", HTTPClient.normalizeEXRResponse(obj));
    }

    @Test
    public void testNormalize_ScalarStringResultKeptWrapped() {
        String body = "{\"result\":\"abcdef0123\",\"error\":null}";
        JsonElement parsed = JsonParser.parseString(body);
        assertEquals(body, HTTPClient.normalizeEXRResponse(parsed));
    }

    @Test
    public void testNormalize_ErrorEnvelopePassthrough() {
        String body = "{\"result\":null,\"error\":{\"code\":-32000,\"message\":\"boom\"}}";
        JsonElement parsed = JsonParser.parseString(body);
        assertEquals(body, HTTPClient.normalizeEXRResponse(parsed));
    }

    @Test
    public void testNormalize_NullAndJsonNullYieldNull() {
        assertNull(HTTPClient.normalizeEXRResponse(null));
        assertNull(HTTPClient.normalizeEXRResponse(JsonParser.parseString("null")));
    }

    @Test
    public void testNormalize_NonJsonTextInResultFallsBackToPassthrough() {
        JsonObject obj = new JsonObject();
        obj.addProperty("result", "not-json {");
        assertEquals(obj.toString(), HTTPClient.normalizeEXRResponse(obj));
    }

    // --- parseEnvelope ---

    @Test
    public void testParseEnvelope_ValidEnvelopeReturnsObjectWithResult() {
        JsonObject env = HTTPClient.parseEnvelope("{\"result\":5,\"error\":null}", "op");
        assertNotNull(env);
        assertEquals(5, env.get("result").getAsInt());
    }

    @Test
    public void testParseEnvelope_ScalarStringResultPreserved() {
        JsonObject env = HTTPClient.parseEnvelope("{\"result\":\"<hex>\",\"error\":null}", "op");
        assertNotNull(env);
        assertEquals("<hex>", env.get("result").getAsString());
    }

    @Test
    public void testParseEnvelope_ObjectResultPreserved() {
        JsonObject env = HTTPClient.parseEnvelope("{\"result\":{\"hex\":\"aa\"},\"error\":null}", "op");
        assertNotNull(env);
        assertEquals("aa", env.getAsJsonObject("result").get("hex").getAsString());
    }

    @Test
    public void testParseEnvelope_ErrorYieldsNull() {
        assertNull(HTTPClient.parseEnvelope(
                "{\"result\":null,\"error\":{\"code\":-25,\"message\":\"missing inputs\"}}", "op"));
    }

    @Test
    public void testParseEnvelope_NullResultYieldsNull() {
        assertNull(HTTPClient.parseEnvelope("{\"result\":null,\"error\":null}", "op"));
    }

    @Test
    public void testParseEnvelope_MissingResultKeyYieldsNull() {
        assertNull(HTTPClient.parseEnvelope("{\"foo\":1}", "op"));
    }

    @Test
    public void testParseEnvelope_NonObjectPayloadYieldsNull() {
        assertNull(HTTPClient.parseEnvelope("[1,2]", "op"));
        assertNull(HTTPClient.parseEnvelope("\"str\"", "op"));
    }

    @Test
    public void testParseEnvelope_MalformedJsonYieldsNull() {
        assertNull(HTTPClient.parseEnvelope("{nope", "op"));
    }

    @Test
    public void testParseEnvelope_NullInputYieldsNull() {
        assertNull(HTTPClient.parseEnvelope(null, "op"));
    }

    // --- extractCoin ---

    @Test
    public void testExtractCoin_ValidTicker() {
        JsonArray params = JsonParser.parseString("[\"BLOCK\",[\"addr\"]]").getAsJsonArray();
        assertEquals(CoinTicker.BLOCKNET, HTTPClient.extractCoin(params));
    }

    @Test
    public void testExtractCoin_UnknownTickerYieldsNull() {
        JsonArray params = JsonParser.parseString("[\"NOPE\",[\"addr\"]]").getAsJsonArray();
        assertNull(HTTPClient.extractCoin(params));
    }

    @Test
    public void testExtractCoin_NonStringFirstParamYieldsNull() {
        JsonArray params = JsonParser.parseString("[42,\"x\"]").getAsJsonArray();
        assertNull(HTTPClient.extractCoin(params));
    }

    @Test
    public void testExtractCoin_EmptyParamsYieldNull() {
        assertNull(HTTPClient.extractCoin(new JsonArray()));
    }

    // --- sendRawTransaction envelope passthrough ---

    static class CannedClient extends HTTPClient {
        final String canned;

        CannedClient(String canned) {
            super(2);
            this.canned = canned;
        }

        @Override
        String executeRequest(String endpoint, JsonObject params) {
            return canned;
        }
    }

    @Test
    public void testSendRawTransaction_ErrorEnvelopePassedThroughIntact() {
        HTTPClient client = new CannedClient(
                "{\"result\":null,\"error\":{\"code\":-25,\"message\":\"missing inputs\"}}");
        JsonObject res = client.sendRawTransaction(CoinTicker.BLOCKNET, "deadbeef");
        assertNotNull(res);
        assertTrue(res.has("error") && !res.get("error").isJsonNull());
        assertEquals(-25, res.getAsJsonObject("error").get("code").getAsInt());
        assertEquals("missing inputs", res.getAsJsonObject("error").get("message").getAsString());
    }

    @Test
    public void testSendRawTransaction_LegacyIntErrorEnvelopePassedThrough() {
        HTTPClient client = new CannedClient("{\"result\":null,\"error\":-4}");
        JsonObject res = client.sendRawTransaction(CoinTicker.BLOCKNET, "deadbeef");
        assertNotNull(res);
        assertEquals(-4, res.get("error").getAsInt());
    }

    @Test
    public void testSendRawTransaction_SuccessEnvelopePassedThrough() {
        HTTPClient client = new CannedClient("{\"result\":\"abcdef\",\"error\":null}");
        JsonObject res = client.sendRawTransaction(CoinTicker.BLOCKNET, "deadbeef");
        assertNotNull(res);
        assertEquals("abcdef", res.get("result").getAsString());
    }

    @Test
    public void testSendRawTransaction_MalformedJsonYieldsNull() {
        HTTPClient client = new CannedClient("{nope");
        assertNull(client.sendRawTransaction(CoinTicker.BLOCKNET, "deadbeef"));
    }

    @Test
    public void testSendRawTransaction_NullResponseYieldsNull() {
        HTTPClient client = new CannedClient(null);
        assertNull(client.sendRawTransaction(CoinTicker.BLOCKNET, "deadbeef"));
    }
}
