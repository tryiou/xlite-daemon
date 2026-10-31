package io.xlite.daemon.app.net.api.http.client;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.subgraph.orchid.encoders.Hex;

import io.xlite.daemon.app.App;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.UTXO;
import io.xlite.daemon.app.util.history.Transaction;

import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HTTPClient {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private CloseableHttpClient client;
    private ConcurrentHashMap<String, Long> lastFetchTimes;

    /**
     * Upstream method families for per-coin support tracking. A family groups the read methods
     * served by the same backend capability, so one definitive rejection covers every method in the
     * family.
     */
    static final String UPSTREAM_FAMILY_UTXO = "utxo";

    static final String UPSTREAM_FAMILY_HISTORY = "history";
    static final String UPSTREAM_FAMILY_TX = "tx";
    static final String UPSTREAM_FAMILY_BLOCK = "block";

    /**
     * Distinctive backend message proving a coin has no upstream plugin. Matched as a substring,
     * deliberately biased toward precision: a missed match degrades to today's retry behavior,
     * while a false positive would suppress a working coin.
     *
     * <p>The code alone ({@code -32000}) is not sufficient: the backend reuses it for transient
     * failures ({@code "utxo-plugin unreachable …"}, batch failures) and for address-level failures
     * ({@code "All addresses failed"}), none of which may be cached.
     */
    /** How many times one raw transaction is fetched before giving up. */
    private static final int MAX_RAW_TX_ATTEMPTS = 5;

    static final String NO_UPSTREAM_MARKER = "No utxo-plugin configured for coin ";

    /**
     * (family, ticker) pairs the backend definitively rejected this session. The backend reads its
     * coin list once at startup, so it cannot gain a plugin without a restart; a daemon restart
     * clears this with it. Legacy mode only: the backend is singular there, while EXR selects among
     * multiple servers and a global record could suppress a working one.
     */
    private static final Set<String> UNSUPPORTED_UPSTREAM = ConcurrentHashMap.newKeySet();

    static String upstreamRejectionKey(String family, CoinTicker ticker) {
        return family + "|" + CoinTickerUtils.tickerToString(ticker);
    }

    /**
     * Whether this (family, coin) was definitively rejected upstream. Legacy-mode callers check
     * this before opening a socket and return null, the existing "not ready" contract every caller
     * already handles.
     *
     * @param family One of the {@code UPSTREAM_FAMILY_*} constants
     * @param ticker Coin to look up
     * @return true when no request should be issued
     */
    static boolean isUpstreamRejected(String family, CoinTicker ticker) {
        return UNSUPPORTED_UPSTREAM.contains(upstreamRejectionKey(family, ticker));
    }

    /** Test-only reset for the session rejection memory. */
    static void clearUpstreamRejectionsForTest() {
        UNSUPPORTED_UPSTREAM.clear();
    }

    /**
     * Record a definitive no-upstream rejection carried by a raw upstream response. Accepts the
     * producer envelope ({@code {"result":null,"error":{"code":…,"message":…}}}) and, tolerantly, a
     * bare ({@code {"code":…,"message":…}}) object.
     *
     * @param family One of the {@code UPSTREAM_FAMILY_*} constants
     * @param ticker Coin the response was fetched for
     * @param res Raw upstream response text
     * @return true iff the response is definitive (caller returns null); false on any doubt,
     *     preserving today's retry behavior (fail-open)
     */
    static boolean recordUpstreamRejection(String family, CoinTicker ticker, String res) {
        if (!isNoUpstreamError(upstreamError(res))) {
            return false;
        }
        String key = upstreamRejectionKey(family, ticker);
        if (UNSUPPORTED_UPSTREAM.add(key)) {
            LOGGER.info(
                    "[httpclient] "
                            + CoinTickerUtils.tickerToString(ticker)
                            + " has no upstream for "
                            + family
                            + " - will not retry this session");
        }
        return true;
    }

    /**
     * Whether an upstream error is the adapter's definitive "no plugin for this coin" rejection.
     */
    private static boolean isNoUpstreamError(JsonObject error) {
        if (error == null || !error.has("code") || !error.has("message")) {
            return false;
        }
        try {
            JsonElement code = error.get("code");
            JsonElement message = error.get("message");
            return code.isJsonPrimitive()
                    && code.getAsJsonPrimitive().isNumber()
                    && code.getAsInt() == -32000
                    && message.isJsonPrimitive()
                    && message.getAsJsonPrimitive().isString()
                    && message.getAsString().contains(NO_UPSTREAM_MARKER);
        } catch (RuntimeException e) {
            // Deliberately broad: a fail-open helper must never throw on odd shapes.
            return false;
        }
    }

    /**
     * Extract the candidate error object from a raw upstream response: the {@code error} member of
     * an envelope, else the object itself for bare-shape tolerance (the caller decides via {@code
     * has("code")}). Null when absent or unparseable. Never throws.
     *
     * @param res Raw upstream response text
     * @return Error object or null
     */
    static JsonObject upstreamError(String res) {
        if (res == null) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(res);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject obj = parsed.getAsJsonObject();
            if (obj.has("error") && obj.get("error").isJsonObject()) {
                return obj.getAsJsonObject("error");
            }
            return obj;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Helper method to wait for EXR capabilities to be probed with a timeout.
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if capabilities are probed within timeout, false otherwise
     */
    private boolean waitForCapabilities(int timeoutMs) {
        if (!useEXR()) {
            return false;
        }

        if (App.getExrServerPool().isCapabilitiesProbed()) {
            return true;
        }

        LOGGER.fine(
                "[httpclient] Waiting for EXR capabilities to be probed (timeout: "
                        + timeoutMs
                        + "ms)");

        int waitTime = 0;
        while (!App.getExrServerPool().isCapabilitiesProbed() && waitTime < timeoutMs) {
            try {
                Thread.sleep(HttpClientConfig.CAPABILITY_PROBE_WAIT_INTERVAL_MS);
                waitTime += HttpClientConfig.CAPABILITY_PROBE_WAIT_INTERVAL_MS;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warning("[httpclient] Waiting for capabilities was interrupted");
                return false;
            }
        }

        boolean probed = App.getExrServerPool().isCapabilitiesProbed();
        LOGGER.fine(
                "[httpclient] EXR capabilities "
                        + (probed ? "probed successfully" : "still not probed")
                        + " after waiting "
                        + waitTime
                        + "ms");

        return probed;
    }

    /**
     * Helper method to check if EXR pool is available and configured.
     *
     * @return true if EXR pool is configured, false otherwise
     */
    boolean useEXR() {
        return App.getExrServerPool() != null && App.getExrServerPool().getServerCount() > 0;
    }

    /**
     * Check if EXR should be used for the given endpoint
     *
     * @param endpoint The API endpoint
     * @return true if EXR should be used, false otherwise
     */
    boolean shouldUseEXR(String endpoint) {
        return useEXR()
                && (endpoint.equals("/fees") || endpoint.equals("/height") || endpoint.equals("/"));
    }

    /**
     * Convert JsonArray to List<Object> for EXR execution
     *
     * @param exrParams JsonArray of parameters
     * @return List of parameters
     */
    private List<Object> convertParams(JsonArray exrParams) {
        List<Object> paramList = new ArrayList<>();
        for (JsonElement element : exrParams) {
            if (element.isJsonPrimitive()) {
                JsonPrimitive primitive = element.getAsJsonPrimitive();
                if (primitive.isString()) {
                    paramList.add(primitive.getAsString());
                } else if (primitive.isNumber()) {
                    paramList.add(primitive.getAsNumber());
                } else if (primitive.isBoolean()) {
                    paramList.add(primitive.getAsBoolean());
                }
            } else {
                // For complex objects, convert to string
                paramList.add(element.toString());
            }
        }
        return paramList;
    }

    /**
     * Execute GET request with common pattern
     *
     * @param endpoint The endpoint to GET
     * @return Response string or null on error
     */
    private String executeGetRequest(String endpoint) {
        HttpGet httpGet = new HttpGet(App.getBaseUrl() + endpoint);
        return HttpUtils.executeHttpRequest(client, httpGet, "GET " + endpoint);
    }

    /**
     * Execute POST request with common pattern
     *
     * @param endpoint The endpoint to POST to
     * @param params The parameters to POST
     * @return Response string or null on error
     */
    private String executePostRequest(String endpoint, JsonObject params) {
        HttpPost httpPost = new HttpPost();
        httpPost.setURI(URI.create(App.getBaseUrl() + endpoint));
        try {
            httpPost.setEntity(new StringEntity(params.toString()));
        } catch (UnsupportedEncodingException e) {
            LOGGER.warning(
                    "executePostRequest failed to set entity "
                            + endpoint
                            + " err: "
                            + e.getMessage());
            httpPost.reset();
            return null;
        }
        return HttpUtils.executeHttpRequest(client, httpPost, describePost(endpoint, params));
    }

    /**
     * Builds a context-rich operation label from what's already in hand: every legacy POST caller
     * sends {method, params:[first, ...]} where the first param is the ticker for coin-first-param
     * methods. Never throws: a description must not break the request it describes.
     */
    private static String describePost(String endpoint, JsonObject params) {
        String method = "?";
        String first = "";
        try {
            if (params != null && params.has("method") && params.get("method").isJsonPrimitive()) {
                method = params.get("method").getAsString();
            }
            if (params != null && params.has("params") && params.get("params").isJsonArray()) {
                JsonArray arr = params.getAsJsonArray("params");
                if (arr.size() > 0 && arr.get(0).isJsonPrimitive()) {
                    first = " " + arr.get(0).getAsString();
                }
            }
        } catch (Exception ignored) {
            // Fall through with whatever was extracted; worst case "?".
        }
        return "POST " + endpoint + " [" + method + first + "]";
    }

    public HTTPClient(int maximumSockets) {
        lastFetchTimes = new ConcurrentHashMap<>();

        Header header = new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        List<Header> headers = Lists.newArrayList(header);

        RequestConfig.Builder requestBuilder = RequestConfig.custom();
        requestBuilder.setConnectTimeout(HttpClientConfig.HTTP_TIMEOUT_MS);
        requestBuilder.setConnectionRequestTimeout(HttpClientConfig.HTTP_TIMEOUT_MS);
        requestBuilder.setSocketTimeout(HttpClientConfig.HTTP_TIMEOUT_MS);

        PoolingHttpClientConnectionManager connectionManager =
                new PoolingHttpClientConnectionManager(
                        RegistryBuilder.<ConnectionSocketFactory>create()
                                .register("http", PlainConnectionSocketFactory.INSTANCE)
                                .register(
                                        "https",
                                        SSLConnectionSocketFactory.getSystemSocketFactory())
                                .build());
        connectionManager.setDefaultMaxPerRoute(maximumSockets);
        connectionManager.setMaxTotal(maximumSockets);

        client =
                HttpClients.custom()
                        .setDefaultHeaders(headers)
                        .setConnectionManager(connectionManager)
                        .setDefaultRequestConfig(requestBuilder.build())
                        .build();
    }

    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            LOGGER.warning("[httpclient] Failed to close HTTP client" + e.getMessage());
        }
    }

    /**
     * Aggregate responses from all healthy EXR servers for a given method
     *
     * @param method The EXR method to call (e.g., "fees", "heights")
     * @return Aggregated response from all healthy EXR servers
     */
    private String aggregateEXRResponse(String method) {
        // Aggregate from ALL EXR servers
        JsonObject aggregatedResult = new JsonObject();
        JsonArray aggregatedErrors = new JsonArray();

        for (EXRServer server : App.getExrServerPool().getServers()) {
            if (!server.isHealthy()) {
                continue;
            }

            try {
                JsonElement response = server.executeGet(method);
                if (response != null && response.isJsonObject()) {
                    JsonObject result = response.getAsJsonObject();
                    if (result.has("result") && result.get("result").isJsonObject()) {
                        JsonObject serverObj = result.getAsJsonObject("result");
                        for (String key : serverObj.keySet()) {
                            if (!aggregatedResult.has(key)) {
                                aggregatedResult.add(key, serverObj.get(key));
                            }
                        }
                    }
                }
                server.probeCapabilities();
            } catch (Exception e) {
                aggregatedErrors.add("Failed " + method + " from " + server.getEndpoint());
            }
        }

        JsonObject finalResult = new JsonObject();
        finalResult.add("result", aggregatedResult);
        finalResult.add("errors", aggregatedErrors);

        return finalResult.toString();
    }

    /**
     * Execute GET request with EXR aggregation for /fees and /height endpoints
     *
     * @param endpoint The endpoint to GET
     * @return Aggregated response from all healthy EXR servers
     */
    private String executeEXRGet(String endpoint) {
        String method = endpoint.equals("/fees") ? "fees" : "heights";
        return aggregateEXRResponse(method);
    }

    /**
     * Extract the coin ticker from the first parameter of an EXR request.
     *
     * @param exrParams The parameter array
     * @return Ticker or null if the first parameter is not a known coin
     */
    static CoinTicker extractCoin(JsonArray exrParams) {
        if (exrParams.size() == 0
                || !exrParams.get(0).isJsonPrimitive()
                || !exrParams.get(0).getAsJsonPrimitive().isString()) {
            return null;
        }
        return CoinTickerUtils.stringToTicker(exrParams.get(0).getAsString());
    }

    /**
     * Execute POST request with EXR coin-aware routing
     *
     * @param params The parameters to POST
     * @return Response from appropriate EXR server, or null (FAIL - NO FALLBACK TO BASE_URL)
     */
    private String executeEXRPost(JsonObject params) {
        if (!params.has("method") || !params.has("params")) {
            return null;
        }
        String method = params.get("method").getAsString();
        JsonArray exrParams = params.getAsJsonArray("params");

        CoinTicker coin = extractCoin(exrParams);
        if (coin == null) {
            LOGGER.severe(
                    "[httpclient] Cannot route request: no coin extracted from first parameter for"
                            + " method "
                            + method);
            return null; // FAIL instead of using wrong server
        }

        if (!App.getExrServerPool().isCapabilitiesProbed()
                && !waitForCapabilities(HttpClientConfig.CAPABILITY_PROBE_WAIT_TIMEOUT_MS)) {
            LOGGER.warning(
                    "[httpclient] EXR capabilities not probed yet for coin: "
                            + CoinTickerUtils.tickerToString(coin));
            return null; // FAIL - NO FALLBACK TO BASE_URL
        }

        EXRServer server = App.getExrServerPool().selectServerForCoin(coin);
        if (server == null) {
            LOGGER.warning(
                    "[httpclient] NO EXR SERVER SUPPORTS COIN: "
                            + CoinTickerUtils.tickerToString(coin));
            return null; // FAIL - NO FALLBACK TO BASE_URL
        }
        LOGGER.info(
                "[httpclient] Routed "
                        + method
                        + " "
                        + CoinTickerUtils.tickerToString(coin)
                        + " to "
                        + server.getEndpoint());

        List<Object> paramList = convertParams(exrParams);
        JsonElement response = server.execute(method, paramList);
        if (response == null) {
            LOGGER.warning(
                    "[httpclient] EXR request failed for "
                            + method
                            + " via "
                            + server.getEndpoint());
            return null;
        }
        return normalizeEXRResponse(response);
    }

    /**
     * Normalize a parsed EXR response body into the text form consumers expect. Upstream payload
     * shapes are preserved verbatim: JSON-RPC style envelopes, bare objects and bare arrays all
     * pass through unchanged. A JSON text delivered as a string-typed {@code result} member is
     * unwrapped exactly once; scalar string results (e.g. broadcast txids) are kept wrapped.
     *
     * @param response Parsed response element
     * @return Response text or null if the element carried no content
     */
    static String normalizeEXRResponse(JsonElement response) {
        if (response == null || response.isJsonNull()) {
            return null;
        }
        if (response.isJsonObject()) {
            JsonObject obj = response.getAsJsonObject();
            if (obj.has("result")
                    && obj.get("result").isJsonPrimitive()
                    && obj.get("result").getAsJsonPrimitive().isString()) {
                try {
                    JsonElement inner = JsonParser.parseString(obj.get("result").getAsString());
                    if (inner.isJsonObject() || inner.isJsonArray()) {
                        return inner.toString();
                    }
                } catch (JsonSyntaxException e) {
                    // Not JSON text — pass the envelope through unchanged
                }
            }
            return obj.toString();
        }
        return response.toString();
    }

    /**
     * Parse an upstream JSON-RPC style envelope ({@code {"result":…,"error":…}}), returning null
     * (with a logged reason) when the payload is absent, empty, an error, malformed, or not an
     * object. Consumers receive the full envelope so they can read the {@code result} member
     * exactly as with the legacy backend.
     *
     * @param res Raw response text
     * @param opName Operation description for log messages
     * @return Envelope object or null on failure
     */
    static JsonObject parseEnvelope(String res, String opName) {
        if (res == null) {
            return null;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(res);
        } catch (JsonSyntaxException e) {
            LOGGER.warning("[httpclient] " + opName + " invalid upstream JSON - " + e.getMessage());
            return null;
        }
        if (!parsed.isJsonObject()) {
            LOGGER.warning("[httpclient] " + opName + " unexpected upstream payload shape");
            return null;
        }
        JsonObject obj = parsed.getAsJsonObject();
        if (obj.has("error") && !obj.get("error").isJsonNull()) {
            LOGGER.warning(
                    "[httpclient] " + opName + " upstream error - " + obj.get("error").toString());
            return null;
        }
        if (!obj.has("result") || obj.get("result").isJsonNull()) {
            LOGGER.warning("[httpclient] " + opName + " empty upstream result");
            return null;
        }
        return obj;
    }

    /**
     * Execute request with proper routing logic (EXR vs BASE_URL)
     *
     * @param endpoint The endpoint to request
     * @param params Parameters for POST requests, null for GET
     * @return Response string or null on error
     */
    String executeRequest(String endpoint, JsonObject params) {
        // When EXR is configured, ONLY use EXR - NO fallback to BASE_URL
        if (shouldUseEXR(endpoint)) {
            if (params == null) {
                // GET request
                return executeEXRGet(endpoint);
            } else {
                // POST request
                return executeEXRPost(params);
            }
        }

        // ONLY fall back to BASE_URL when EXR is NOT configured
        if (!useEXR()) {
            if (params == null) {
                // GET request
                return executeGetRequest(endpoint);
            } else {
                // POST request
                return executePostRequest(endpoint, params);
            }
        }

        return null; // EXR configured but no valid response
    }

    /**
     * Returns all utxos for a list of addresses. Note: This method does neither use nor update any
     * caches!
     *
     * @param coinTicker Fetch utxos from this coin
     * @param addresses Fetch utxos from these addresses
     * @return JsonArray or null on error
     */
    public JsonArray getUtxosUncached(CoinTicker coinTicker, String[] addresses) {
        // Coin has no upstream plugin: skip the request entirely (legacy mode only).
        if (!useEXR() && isUpstreamRejected(UPSTREAM_FAMILY_UTXO, coinTicker)) {
            return null;
        }
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);

        JsonArray innerParams = new JsonArray();
        innerParams.add(CoinTickerUtils.tickerToString(coinTicker));
        innerParams.add(new Gson().toJsonTree(addresses).getAsJsonArray());

        JsonObject params = new JsonObject();
        params.addProperty("method", "getutxos");
        params.add("params", innerParams);
        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] getUtxosUncached " + coinInstance.getTicker() + " " + res);

        if (res == null) {
            return null;
        }
        // Definitive no-upstream rejection becomes this session's answer.
        if (!useEXR() && recordUpstreamRejection(UPSTREAM_FAMILY_UTXO, coinTicker, res)) {
            return null;
        }
        UtxoResponse response = parseUtxoResponse(coinInstance, res, "getUtxosUncached");
        if (response.rows() == null) {
            return null;
        }
        JsonArray utxoList = new JsonArray();
        for (int i = 0; i < response.rows().length(); i++) {
            utxoList.add(utxoJson(coinTicker, response.rows().getJSONObject(i)));
        }
        return utxoList;
    }

    /** One provider UTXO row as the RPC surface states it. */
    private static JsonObject utxoJson(CoinTicker coinTicker, JSONObject row) {
        JsonObject utxo = new JsonObject();
        String address = row.getString("address");
        utxo.addProperty("txid", row.getString("txhash"));
        utxo.addProperty("vout", row.getInt("vout"));
        utxo.addProperty("value", row.getDouble("value"));
        utxo.addProperty("spendable", true);
        utxo.addProperty("address", address);

        LegacyAddress addr =
                LegacyAddress.fromBase58(
                        CoinInstance.getInstance(coinTicker).getNetworkParameters(), address);
        Script script = ScriptBuilder.createOutputScript(addr);
        utxo.addProperty(
                "scriptPubKey",
                new String(Hex.encode(script.getProgram()), StandardCharsets.UTF_8));

        int height = row.getInt("block_number");
        int confirmations =
                height == 0 ? 0 : CoinInstance.getBlockCountByTicker(coinTicker) - height + 1;
        utxo.addProperty("confirmations", confirmations);
        return utxo;
    }

    /** A provider UTXO response: its body and {@code utxos} array, either of which may be null. */
    private record UtxoResponse(JSONObject body, JSONArray rows) {}

    /** The body and {@code utxos} array of a provider response; rows is null when unusable. */
    private static UtxoResponse parseUtxoResponse(
            CoinInstance coinInstance, String res, String call) {
        JSONObject jsonObject = null;
        JSONArray utxoArr = null;
        try {
            jsonObject = new JSONObject(res);
            utxoArr = jsonObject.getJSONArray("utxos");
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[httpclient] "
                            + call
                            + " "
                            + coinInstance.getTicker()
                            + " parse error - "
                            + e.getMessage());
        }
        if (jsonObject == null) {
            LOGGER.warning(
                    "[httpclient] " + call + " " + coinInstance.getTicker() + " null jsonObject");
        }
        if (utxoArr == null) {
            LOGGER.warning(
                    "[httpclient] " + call + " " + coinInstance.getTicker() + " null utxoArr");
        }
        return new UtxoResponse(jsonObject, utxoArr);
    }

    /**
     * Returns all utxos.
     *
     * @param coinTicker Fetch utxos from this coin
     * @param expiry Time in milliseconds until cache expires
     * @return JsonArray or null on error
     */
    public JsonArray getUtxos(CoinTicker coinTicker, int expiry) {
        // Coin has no upstream plugin: skip the request entirely (legacy mode only).
        if (!useEXR() && isUpstreamRejected(UPSTREAM_FAMILY_UTXO, coinTicker)) {
            return null;
        }
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        long lastFetchTime =
                lastFetchTimes.getOrDefault(lastFetchTimesKey(coinTicker, "getUtxos"), 0L);
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFetchTime < expiry) {
            return coinInstance.getAllUTXOS();
        }

        List<String> utxoParams = coinInstance.getUTXOParams();
        if (utxoParams.isEmpty()) {
            LOGGER.warning(
                    "[httpclient] getUtxos " + coinInstance.getTicker() + " null param size");
            return null;
        }

        JsonObject params = new JsonObject();
        params.addProperty("method", "getutxos");
        params.add("params", new Gson().toJsonTree(utxoParams).getAsJsonArray());
        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] getUtxos " + coinInstance.getTicker() + " " + res);

        if (res == null) {
            return null;
        }
        // Definitive no-upstream rejection becomes this session's answer.
        if (!useEXR() && recordUpstreamRejection(UPSTREAM_FAMILY_UTXO, coinTicker, res)) {
            return null;
        }
        UtxoResponse response = parseUtxoResponse(coinInstance, res, "getUtxos");
        if (response.rows() == null) {
            return null;
        }
        UtxoBatch batch = readUtxoBatch(coinTicker, response.body(), response.rows());

        // Evict rows for requested-but-backend-omitted addresses. A successful response that
        // omits a tracked address (or flags it in the backend's "errors" array) means the
        // backend holds no unspent outputs for it — any stored rows are confirmed-stale (a
        // lagging view re-serves dead outputs with live-looking confirmations otherwise).
        // processUtxos below only clears addresses PRESENT in the response, so without this an
        // omitted address keeps its rows forever. Runs on parsed-success only: null/parse-error
        // responses return above with stored rows untouched (an outage must never wipe the
        // wallet).
        evictOmittedAddresses(coinInstance, utxoParams, batch.addresses());

        // Update last fetch time
        lastFetchTimes.put(lastFetchTimesKey(coinTicker, "getUtxos"), currentTime);

        coinInstance.processUtxos(batch.utxos());
        return coinInstance.getAllUTXOS();
    }

    /**
     * The UTXOs a provider response describes, and every address it did return.
     *
     * <p>Backend-declared per-address failures ("errors" array the adapter attaches) are treated as
     * omitted entirely: their rows are neither added nor kept stored.
     */
    private UtxoBatch readUtxoBatch(CoinTicker coinTicker, JSONObject body, JSONArray rows) {
        List<UTXO> utxos = new ArrayList<>();
        Set<String> returnedAddresses = new HashSet<>();
        Set<String> failedAddresses = failedAddresses(body);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String address = row.getString("address");
            if (failedAddresses.contains(address)) {
                continue;
            }
            utxos.add(
                    new UTXO(
                            coinTicker,
                            address,
                            row.getString("txhash"),
                            row.getInt("vout"),
                            row.getInt("block_number"),
                            UTXO.baseUnitsForTicker(coinTicker, row.getDouble("value"))));
            returnedAddresses.add(address);
        }
        return new UtxoBatch(utxos, returnedAddresses);
    }

    /** The parsed UTXOs of one response, and the addresses they cover. */
    private record UtxoBatch(List<UTXO> utxos, Set<String> addresses) {}

    /**
     * Reads the backend-declared per-address failures from a getutxos response ("errors" array of
     * {address, message} items the adapter attaches; absent/null on clean responses). Never throws:
     * an unparseable array degrades to a (possibly partial) set (the omission diff below still
     * applies).
     */
    Set<String> failedAddresses(JSONObject response) {
        Set<String> failed = new HashSet<>();
        try {
            JSONArray errors = response.optJSONArray("errors");
            if (errors != null) {
                for (int i = 0; i < errors.length(); i++) {
                    String addr = errors.getJSONObject(i).optString("address", null);
                    if (addr != null) {
                        failed.add(addr);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warning(
                    "[httpclient] failedAddresses unparseable errors array - " + e.getMessage());
        }
        return failed;
    }

    /**
     * Clears stored UTXO rows for tracked addresses the backend omitted from a successful getutxos
     * response. requestedParams is the [ticker, "[addr...]"] list built by
     * CoinInstance.getUTXOParams (the second element is a stringified JSONArray); returned holds
     * the addresses present in the response. Backend-declared failures never reach returned (their
     * rows are skipped before recording above), so a requested address missing from returned is
     * confirmed to hold nothing. Unknown/non-tracked addresses are ignored (processUtxos warns on
     * those itself). clearUtxos spares spent-marked rows, so only live-looking stale rows are
     * dropped.
     */
    void evictOmittedAddresses(
            CoinInstance coinInstance, List<String> requestedParams, Set<String> returned) {
        Set<String> requested = new HashSet<>();
        try {
            if (requestedParams.size() > 1) {
                JSONArray arr = new JSONArray(requestedParams.get(1));
                for (int i = 0; i < arr.length(); i++) {
                    requested.add(arr.getString(i));
                }
            }
        } catch (Exception e) {
            LOGGER.warning(
                    "[httpclient] evictOmittedAddresses unparseable request params - "
                            + e.getMessage());
            return;
        }
        for (String addr : requested) {
            if (returned.contains(addr)) {
                continue;
            }
            AddressBalance balance = coinInstance.getAddressBalance(addr);
            if (balance == null) {
                continue;
            }
            balance.clearUtxos();
            // clearUtxos drops rows but leaves the cached balance behind;
            // without this the address keeps reporting its old getbalance
            // after eviction (the normal path always re-adds rows, whose
            // addUtxo recalculates — the omit path is the first clear with
            // no following add).
            balance.calculateBalance();
        }
        // Routine success is silent by operator order: this runs on every
        // GUI poll per coin, and an INFO here is pure log spam. Real
        // failures (unparseable params above) still warn.
    }

    public JsonObject getRawTransaction(CoinTicker coinTicker, String txid, boolean verbose) {
        // Coin has no upstream plugin: skip the request entirely (legacy mode only).
        if (!useEXR() && isUpstreamRejected(UPSTREAM_FAMILY_TX, coinTicker)) {
            return null;
        }
        ArrayList<String> rawTxParams = new ArrayList<>();
        rawTxParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawTxParams.add(1, txid);
        rawTxParams.add(2, String.valueOf(verbose));

        JsonArray innerParams = new Gson().toJsonTree(rawTxParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getrawtransaction");
        params.add("params", innerParams);
        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] getRawTransaction " + res);

        // Definitive no-upstream rejection becomes this session's answer.
        if (!useEXR() && recordUpstreamRejection(UPSTREAM_FAMILY_TX, coinTicker, res)) {
            return null;
        }
        return parseEnvelope(res, "getRawTransaction");
    }

    public JsonObject getRawMempool(CoinTicker coinTicker, boolean verbose) {
        // Coin has no upstream plugin: skip the request entirely (legacy mode only).
        if (!useEXR() && isUpstreamRejected(UPSTREAM_FAMILY_TX, coinTicker)) {
            return null;
        }
        ArrayList<String> rawMempoolParams = new ArrayList<>();
        rawMempoolParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawMempoolParams.add(1, String.valueOf(verbose));

        JsonArray innerParams = new Gson().toJsonTree(rawMempoolParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getrawmempool");
        params.add("params", innerParams);
        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] getRawMempool " + res);

        // Definitive no-upstream rejection becomes this session's answer.
        if (!useEXR() && recordUpstreamRejection(UPSTREAM_FAMILY_TX, coinTicker, res)) {
            return null;
        }
        return parseEnvelope(res, "getRawMempool");
    }

    public void getBlockCount(CoinTicker coinTicker) {
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        ArrayList<String> blockCountParams = new ArrayList<>();
        blockCountParams.add(0, CoinTickerUtils.tickerToString(coinTicker));

        JsonArray innerParams = new Gson().toJsonTree(blockCountParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getblockcount");
        params.add("params", innerParams);

        String res = executeRequest("/", params);

        JsonObject result = parseEnvelope(res, "getBlockCount " + coinTicker);
        if (result == null) {
            return;
        }

        int blockCount = result.get("result").getAsInt();

        coinInstance.addBlockCount(coinTicker, blockCount);

        LOGGER.finer("[httpclient] Got blockcount for currency " + coinTicker + " - " + blockCount);
    }

    public void getAllBlockCounts() {
        String res = executeRequest("/height", null);

        if (res == null) {
            // Total backend outage: every coin misses, not none. The old
            // early return froze all heights with isInstanceRunning stuck
            // true and zero log output — the exact blind spot behind a
            // multi-minute stale tip with a clean log.
            for (CoinInstance coinInstance : CoinInstance.getCoinInstances()) {
                noteHeightMiss(coinInstance, "null /height response");
            }
            return;
        }

        final JsonObject blockCounts;
        try {
            JsonObject result = new Gson().fromJson(res, JsonObject.class);
            blockCounts = result.get("result").getAsJsonObject();
        } catch (RuntimeException e) {
            // Malformed payload (bad JSON, missing/non-object result):
            // same accounting as a null response — every coin misses
            // rather than aborting the update where the generic caller
            // catch buries it outside streak accounting.
            for (CoinInstance coinInstance : CoinInstance.getCoinInstances()) {
                noteHeightMiss(coinInstance, "unparseable /height response");
            }
            return;
        }

        for (CoinInstance coinInstance : CoinInstance.getCoinInstances()) {
            String ticker = CoinTickerUtils.tickerToString(coinInstance.getTicker());

            if (!blockCounts.keySet().contains(ticker) || blockCounts.get(ticker).isJsonNull()) {
                noteHeightMiss(coinInstance, "no " + ticker + " in /height response");
                continue;
            }

            final int blockCount;
            try {
                blockCount = blockCounts.get(ticker).getAsInt();
            } catch (RuntimeException e) {
                // Wrong-typed value (string/object/array where a height
                // belongs): same accounting as a missing entry — this coin
                // misses, the loop continues for the rest.
                noteHeightMiss(coinInstance, "bad " + ticker + " in /height response");
                continue;
            }

            coinInstance.addBlockCount(coinInstance.getTicker(), blockCount);
            int recovered = coinInstance.resetUpdateFailures();
            if (recovered > 0) {
                LOGGER.warning(
                        "[httpclient] "
                                + ticker
                                + " height updates recovered after "
                                + recovered
                                + " consecutive failures; fresh height "
                                + blockCount);
            }

            LOGGER.finer("[httpclient] Got blockcount for currency " + ticker + " - " + blockCount);
        }
    }

    /**
     * Records one missed height update, LOUD exactly twice per episode: first miss and recovery.
     * Everything between is silent by operator order — a persistent backend gap re-warning every
     * minute is spam, not signal. The -1112s in consumer logs are the visible symptom while a coin
     * is unavailable.
     */
    private static void noteHeightMiss(CoinInstance coinInstance, String reason) {
        String ticker = CoinTickerUtils.tickerToString(coinInstance.getTicker());
        int streak = coinInstance.incrementUpdateFailures();
        if (streak != 1) {
            return;
        }
        long ageSec =
                coinInstance.getLastBlockCountMillis() <= 0
                        ? -1
                        : (System.currentTimeMillis() - coinInstance.getLastBlockCountMillis())
                                / 1000;
        String lastGood =
                ageSec < 0 ? "no height ever received" : "last-good height age " + ageSec + "s";
        LOGGER.warning(
                "[httpclient] "
                        + ticker
                        + " height update failed ("
                        + reason
                        + "); streak 1, serving stale height, "
                        + lastGood);
    }

    public JsonObject getBlock(CoinTicker coinTicker, String hash, boolean verbose) {
        // Coin has no upstream plugin: skip the request entirely (legacy mode only).
        if (!useEXR() && isUpstreamRejected(UPSTREAM_FAMILY_BLOCK, coinTicker)) {
            return null;
        }
        ArrayList<String> rawParams = new ArrayList<>();
        rawParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawParams.add(1, hash);
        rawParams.add(2, String.valueOf(verbose));

        JsonArray innerParams = new Gson().toJsonTree(rawParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getblock");
        params.add("params", innerParams);
        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] getBlock " + res);

        // Definitive no-upstream rejection becomes this session's answer.
        if (!useEXR() && recordUpstreamRejection(UPSTREAM_FAMILY_BLOCK, coinTicker, res)) {
            return null;
        }
        return parseEnvelope(res, "getBlock");
    }

    public JsonObject getBlockHash(CoinTicker coinTicker, int height) {
        // Coin has no upstream plugin: skip the request entirely (legacy mode only).
        if (!useEXR() && isUpstreamRejected(UPSTREAM_FAMILY_BLOCK, coinTicker)) {
            return null;
        }
        JsonArray innerParams = new JsonArray();
        innerParams.add(CoinTickerUtils.tickerToString(coinTicker));
        innerParams.add(height);

        JsonObject params = new JsonObject();
        params.addProperty("method", "getblockhash");
        params.add("params", innerParams);
        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] getBlockHash " + res);

        // Definitive no-upstream rejection becomes this session's answer.
        if (!useEXR() && recordUpstreamRejection(UPSTREAM_FAMILY_BLOCK, coinTicker, res)) {
            return null;
        }
        return parseEnvelope(res, "getBlockHash");
    }

    public JsonObject getTransaction(CoinTicker coinTicker, String txid, boolean verbose) {
        // Coin has no upstream plugin: skip the request entirely (legacy mode only).
        if (!useEXR() && isUpstreamRejected(UPSTREAM_FAMILY_TX, coinTicker)) {
            return null;
        }
        ArrayList<String> rawParams = new ArrayList<>();
        rawParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawParams.add(1, txid);
        rawParams.add(2, String.valueOf(verbose));

        JsonArray innerParams = new Gson().toJsonTree(rawParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "gettransaction");
        params.add("params", innerParams);
        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] getTransaction " + res);

        // Definitive no-upstream rejection becomes this session's answer.
        if (!useEXR() && recordUpstreamRejection(UPSTREAM_FAMILY_TX, coinTicker, res)) {
            return null;
        }
        return parseEnvelope(res, "getTransaction");
    }

    public JsonObject sendRawTransaction(CoinTicker coinTicker, String rawTx) {
        ArrayList<String> rawTxParams = new ArrayList<>();
        rawTxParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawTxParams.add(1, rawTx);

        JsonArray innerParams = new Gson().toJsonTree(rawTxParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "sendrawtransaction");
        params.add("params", innerParams);
        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] sendRawTransaction " + res);

        // Full envelope passthrough (including upstream error member) — the
        // handler extracts structured {code,message} details for the GUI.
        if (res == null) {
            return null;
        }
        try {
            return JsonParser.parseString(res).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            LOGGER.warning(
                    "[httpclient] sendRawTransaction invalid upstream JSON - " + e.getMessage());
            return null;
        }
    }

    /**
     * Return all transactions associated with the coin.
     *
     * @param coinTicker Coin
     * @param startTime Beginning of the time frame in unix time
     * @param endTime End of the time frame in unix time
     * @param expiry Time in milliseconds until cache expires
     * @return JsonArray or null on error
     */
    public JsonArray getHistory(CoinTicker coinTicker, int startTime, int endTime, int expiry) {
        // Coin has no upstream plugin: skip the request entirely (legacy mode only).
        if (!useEXR() && isUpstreamRejected(UPSTREAM_FAMILY_HISTORY, coinTicker)) {
            return null;
        }
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        long lastFetchTime =
                lastFetchTimes.getOrDefault(lastFetchTimesKey(coinTicker, "getHistory"), 0L);
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFetchTime < expiry) {
            return filterHistory(coinInstance.getAllTransactions(), startTime, endTime);
        }
        JsonArray json = fetchHistory(coinInstance, coinTicker, HistoryCall.HISTORY);
        if (json == null) {
            return null;
        }
        coinInstance.processHistoryTxs(parseHistory(json));

        JsonArray txs = coinInstance.getAllTransactions();
        lastFetchTimes.put(lastFetchTimesKey(coinTicker, "getHistory"), currentTime);
        return filterHistory(txs, startTime, endTime);
    }

    /**
     * Return all transaction hashes associated with the coin.
     *
     * @param coinTicker Coin
     * @param startTime Beginning of the time frame in unix time
     * @param endTime End of the time frame in unix time
     * @param expiry Time in milliseconds until cache expires
     * @return JsonArray or null on error
     */
    public JsonArray getTransactionHistory(
            CoinTicker coinTicker, int startTime, int endTime, int expiry) {
        // Coin has no upstream plugin: skip the request entirely (legacy mode only).
        if (!useEXR() && isUpstreamRejected(UPSTREAM_FAMILY_HISTORY, coinTicker)) {
            return null;
        }
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        long lastFetchTime =
                lastFetchTimes.getOrDefault(lastFetchTimesKey(coinTicker, "getAddressHistory"), 0L);
        long currentTime = System.currentTimeMillis() / 1000;
        if (currentTime - lastFetchTime < expiry) {
            return filterHistory(coinInstance.getAllTransactions(), startTime, endTime);
        }
        JsonArray json = fetchHistory(coinInstance, coinTicker, HistoryCall.ADDRESS);
        if (json == null) {
            return null;
        }
        coinInstance.processHistoryTxs(parseAddressHistory(coinTicker, coinInstance, json));

        JsonArray txs = coinInstance.getAllTransactions();
        lastFetchTimes.put(lastFetchTimesKey(coinTicker, "getAddressHistory"), currentTime);
        return filterHistory(txs, startTime, endTime);
    }

    /** One history endpoint: the RPC method it calls and the name its logs use. */
    private enum HistoryCall {
        HISTORY("gethistory", "getHistory"),
        ADDRESS("getaddresshistory", "getAddressHistory");

        private final String rpcMethod;
        private final String logName;

        HistoryCall(String rpcMethod, String logName) {
            this.rpcMethod = rpcMethod;
            this.logName = logName;
        }
    }

    /** Fetches and parses one history response, or returns null when the body is unusable. */
    private JsonArray fetchHistory(
            CoinInstance coinInstance, CoinTicker coinTicker, HistoryCall call) {
        List<String> utxoParams = coinInstance.getUTXOParams();
        if (utxoParams.isEmpty()) {
            LOGGER.warning(
                    "[httpclient] "
                            + call.logName
                            + " "
                            + coinInstance.getTicker()
                            + " null param size");
            return null;
        }

        JsonObject params = new JsonObject();
        params.addProperty("method", call.rpcMethod);
        params.add("params", new Gson().toJsonTree(utxoParams).getAsJsonArray());

        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] " + call.logName + " " + coinInstance.getTicker() + " " + res);
        if (res == null) {
            return null;
        }
        // Definitive no-upstream rejection becomes this session's answer.
        if (!useEXR() && recordUpstreamRejection(UPSTREAM_FAMILY_HISTORY, coinTicker, res)) {
            return null;
        }

        JsonArray json;
        try {
            json = new Gson().fromJson(res, JsonArray.class);
        } catch (RuntimeException e) {
            LOGGER.warning(
                    "[httpclient] "
                            + call.logName
                            + " parsing error - Response: "
                            + res
                            + " - "
                            + e.getMessage());
            return null;
        }
        if (json == null) {
            LOGGER.warning(
                    "[httpclient] " + call.logName + " " + coinInstance.getTicker() + " null json");
        }
        return json;
    }

    /** The plain history rows the provider already carries in the shape the wallet stores. */
    private static List<Transaction> parseHistory(JsonArray json) {
        List<Transaction> history = new ArrayList<>();
        for (JsonElement group : json) {
            for (JsonElement element : group.getAsJsonArray()) {
                history.add(historyEntry(element.getAsJsonObject()));
            }
        }
        return history;
    }

    private static Transaction historyEntry(JsonObject record) {
        Transaction tx =
                new Transaction(
                        record.get("address").getAsString(),
                        record.get("txid").getAsString(),
                        record.get("blockhash").getAsString(),
                        record.get("vout").getAsInt(),
                        record.get("amount").getAsDouble(),
                        record.get("confirmations").getAsInt(),
                        record.get("blocktime").getAsInt());
        tx.setCategory(record.get("category").getAsString());
        tx.setFee(record.get("fee").getAsDouble());
        return tx;
    }

    /**
     * History reconstructed from raw transactions: the provider's address history names the
     * transactions, and each one's spends and receives are resolved against the wallet's addresses.
     */
    private List<Transaction> parseAddressHistory(
            CoinTicker coinTicker, CoinInstance coinInstance, JsonArray json) {
        List<Transaction> history = new ArrayList<>();
        for (JsonElement group : json) {
            for (JsonElement element : group.getAsJsonArray()) {
                collectAddressRecord(coinTicker, coinInstance, element.getAsJsonObject(), history);
            }
        }
        return history;
    }

    private void collectAddressRecord(
            CoinTicker coinTicker,
            CoinInstance coinInstance,
            JsonObject record,
            List<Transaction> history) {
        String txid = record.get("tx_hash").getAsString();
        JsonObject rawTransaction = fetchRawTransactionWithRetries(coinTicker, txid);
        if (rawTransaction == null) {
            return;
        }
        SpendContext context = new SpendContext(coinTicker, coinInstance, rawTransaction, txid);
        collectSpends(context, history);
        collectReceives(context, history);
    }

    /** Fetches a raw transaction, retrying a handful of times; null when it never arrived. */
    private JsonObject fetchRawTransactionWithRetries(CoinTicker coinTicker, String txid) {
        int fails = 1;
        while (fails > 0) {
            if (fails >= MAX_RAW_TX_ATTEMPTS) {
                fails = 0;
            }
            try {
                JsonObject response = getRawTransaction(coinTicker, txid, true);
                if (response != null && !response.get("result").isJsonNull()) {
                    return response.getAsJsonObject("result");
                }
                fails++;
            } catch (RuntimeException e) {
                LOGGER.warning("[httpclient] getRawTransaction failed - " + e.getMessage());
                fails++;
            }
        }
        return null;
    }

    private void collectSpends(SpendContext context, List<Transaction> history) {
        for (JsonElement element : context.rawTransaction().get("vin").getAsJsonArray()) {
            JsonObject input = element.getAsJsonObject();
            int vout = input.get("vout").getAsInt();
            JsonObject parent =
                    fetchRawTransactionWithRetries(
                            context.ticker(), input.get("txid").getAsString());
            if (parent == null) {
                continue;
            }
            JsonObject spent = parent.get("vout").getAsJsonArray().get(vout).getAsJsonObject();
            JsonObject scriptPubKey = spent.getAsJsonObject("scriptPubKey");
            if (!hasAddresses(scriptPubKey)) {
                continue;
            }
            for (JsonElement addressElement : scriptPubKey.getAsJsonArray("addresses")) {
                String address = addressElement.getAsString();
                if (isWalletAddress(context.coinInstance(), address)) {
                    history.add(
                            historyEntry(
                                    context.rawTransaction(),
                                    new HistoryRow(
                                            address,
                                            context.txid(),
                                            vout,
                                            spent.get("value").getAsDouble(),
                                            "send")));
                }
            }
        }
    }

    private static void collectReceives(SpendContext context, List<Transaction> history) {
        for (JsonElement element : context.rawTransaction().get("vout").getAsJsonArray()) {
            JsonObject output = element.getAsJsonObject();
            JsonObject scriptPubKey = output.getAsJsonObject("scriptPubKey");
            if (!hasAddresses(scriptPubKey)) {
                continue;
            }
            for (JsonElement addressElement : scriptPubKey.getAsJsonArray("addresses")) {
                String address = addressElement.getAsString();
                if (isWalletAddress(context.coinInstance(), address)) {
                    history.add(
                            historyEntry(
                                    context.rawTransaction(),
                                    new HistoryRow(
                                            address,
                                            context.txid(),
                                            output.get("n").getAsInt(),
                                            output.get("value").getAsDouble(),
                                            "receive")));
                }
            }
        }
    }

    private static boolean hasAddresses(JsonObject scriptPubKey) {
        return scriptPubKey != null
                && !scriptPubKey.isJsonNull()
                && scriptPubKey.has("addresses")
                && !scriptPubKey.get("addresses").isJsonNull();
    }

    private static boolean isWalletAddress(CoinInstance coinInstance, String address) {
        for (AddressBalance balance : coinInstance.getAddressKeyPairs()) {
            if (balance.getAddress().toBase58().equals(address)) {
                return true;
            }
        }
        return false;
    }

    private static Transaction historyEntry(JsonObject rawTransaction, HistoryRow row) {
        Transaction tx =
                new Transaction(
                        row.address(),
                        row.txid(),
                        rawTransaction.get("blockhash").getAsString(),
                        row.vout(),
                        row.value(),
                        rawTransaction.get("confirmations").getAsInt(),
                        rawTransaction.get("blocktime").getAsInt());
        tx.setCategory(row.category());
        tx.setFee(0.0);
        return tx;
    }

    /** The transaction whose history is being reconstructed, and the wallet to match against. */
    private record SpendContext(
            CoinTicker ticker, CoinInstance coinInstance, JsonObject rawTransaction, String txid) {}

    /** One reconstructed history row: who it belongs to, which output, its amount and direction. */
    private record HistoryRow(
            String address, String txid, int vout, double value, String category) {}

    /**
     * Return all transaction hashes associated with the coin.
     *
     * @param coinTicker Coin
     * @param startTime Beginning of the time frame in unix time
     * @param endTime End of the time frame in unix time
     * @param expiry Time in milliseconds until cache expires
     * @return JsonArray or null on error
     */
    private JsonArray filterHistory(JsonArray txs, int startTime, int endTime) {
        if (endTime <= 0) {
            return txs;
        }
        Iterator<JsonElement> it = txs.iterator();
        while (it.hasNext()) {
            JsonObject tx = it.next().getAsJsonObject();
            int txTime = tx.get("time").getAsInt();
            if (txTime < startTime || txTime > endTime) {
                it.remove();
            }
        }
        return txs;
    }

    /**
     * Returns the key used with last fetch times.
     *
     * @param ticker Coin
     * @param method Storage key name
     * @return Storage key
     */
    private String lastFetchTimesKey(CoinTicker ticker, String method) {
        return method + ":" + ticker.name();
    }
}
