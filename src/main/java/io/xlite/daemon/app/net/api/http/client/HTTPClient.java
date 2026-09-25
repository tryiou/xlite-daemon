package io.xlite.daemon.app.net.api.http.client;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.subgraph.orchid.encoders.Hex;
import io.xlite.daemon.app.App;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.Sats;
import io.xlite.daemon.app.util.UTXO;
import io.xlite.daemon.app.util.history.Transaction;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HTTPClient {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private CloseableHttpClient client;
    private ConcurrentHashMap<String, Long> lastFetchTimes;
    private int logCount = 0;

    /**
     * Helper method to wait for EXR capabilities to be probed with a timeout.
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if capabilities are probed within timeout, false otherwise
     */
    private boolean waitForCapabilities(int timeoutMs) {
        if (!useEXR()) {
            return false;
        }

        if (App.exrServerPool.isCapabilitiesProbed()) {
            return true;
        }

        LOGGER.fine("[httpclient] Waiting for EXR capabilities to be probed (timeout: " + timeoutMs + "ms)");

        int waitTime = 0;
        while (!App.exrServerPool.isCapabilitiesProbed() && waitTime < timeoutMs) {
            try {
                Thread.sleep(HttpClientConfig.CAPABILITY_PROBE_WAIT_INTERVAL_MS);
                waitTime += HttpClientConfig.CAPABILITY_PROBE_WAIT_INTERVAL_MS;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warning("[httpclient] Waiting for capabilities was interrupted");
                return false;
            }
        }

        boolean probed = App.exrServerPool.isCapabilitiesProbed();
        LOGGER.fine("[httpclient] EXR capabilities " +
                (probed ? "probed successfully" : "still not probed") +
                " after waiting " + waitTime + "ms");

        return probed;
    }

    /**
     * Helper method to check if EXR pool is available and configured.
     * @return true if EXR pool is configured, false otherwise
     */
    boolean useEXR() {
        return App.exrServerPool != null && App.exrServerPool.getServerCount() > 0;
    }

    /**
     * Check if EXR should be used for the given endpoint
     * @param endpoint The API endpoint
     * @return true if EXR should be used, false otherwise
     */
    boolean shouldUseEXR(String endpoint) {
        return useEXR() && (endpoint.equals("/fees") ||
                endpoint.equals("/height") ||
                endpoint.equals("/"));
    }

    /**
     * Get an available EXR server from the pool.
     * @return EXRServer instance or null if no servers available
     */
    private EXRServer getEXRServer() {
        return useEXR() ? App.exrServerPool.selectServer() : null;
    }

    /**
     * Convert JsonArray to List<Object> for EXR execution
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
     * Execute HTTP request with common boilerplate pattern
     * @param request The HTTP request to execute
     * @return Response string or null on error
     */
    private <T extends HttpRequestBase> String executeHttpRequest(T request) {
        return HttpUtils.executeHttpRequest(client, request, "HTTP request");
    }

    /**
     * Execute GET request with common pattern
     * @param endpoint The endpoint to GET
     * @return Response string or null on error
     */
    private String executeGetRequest(String endpoint) {
        HttpGet httpGet = new HttpGet(App.BASE_URL + endpoint);
        return HttpUtils.executeHttpRequest(client, httpGet, "GET " + endpoint);
    }

    /**
     * Execute POST request with common pattern
     * @param endpoint The endpoint to POST to
     * @param params The parameters to POST
     * @return Response string or null on error
     */
    private String executePostRequest(String endpoint, JsonObject params) {
        HttpPost httpPost = new HttpPost();
        httpPost.setURI(URI.create(App.BASE_URL + endpoint));
        try {
            httpPost.setEntity(new StringEntity(params.toString()));
        } catch (UnsupportedEncodingException e) {
            LOGGER.warning("executePostRequest failed to set entity " + endpoint + " err: " + e.getMessage());
            httpPost.reset();
            return null;
        }
        return HttpUtils.executeHttpRequest(client, httpPost, describePost(endpoint, params));
    }

    /**
     * Builds a context-rich operation label from what's already in hand:
     * every legacy POST caller sends {method, params:[first, ...]} where
     * the first param is the ticker for coin-first-param methods. Never
     * throws: a description must not break the request it describes.
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

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(
                RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("http", PlainConnectionSocketFactory.INSTANCE)
                        .register("https", SSLConnectionSocketFactory.getSystemSocketFactory())
                        .build()
        );
        connectionManager.setDefaultMaxPerRoute(maximumSockets);
        connectionManager.setMaxTotal(maximumSockets);

        client = HttpClients.custom()
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
     * @param method The EXR method to call (e.g., "fees", "heights")
     * @return Aggregated response from all healthy EXR servers
     */
    private String aggregateEXRResponse(String method) {
        // Aggregate from ALL EXR servers
        JsonObject aggregatedResult = new JsonObject();
        JsonArray aggregatedErrors = new JsonArray();

        for (EXRServer server : App.exrServerPool.getServers()) {
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
     * @param endpoint The endpoint to GET
     * @return Aggregated response from all healthy EXR servers
     */
    private String executeEXRGet(String endpoint) {
        String method = endpoint.equals("/fees") ? "fees" : "heights";
        return aggregateEXRResponse(method);
    }

    /**
     * Extract the coin ticker from the first parameter of an EXR request.
     * @param exrParams The parameter array
     * @return Ticker or null if the first parameter is not a known coin
     */
    static CoinTicker extractCoin(JsonArray exrParams) {
        if (exrParams.size() == 0 || !exrParams.get(0).isJsonPrimitive()
                || !exrParams.get(0).getAsJsonPrimitive().isString()) {
            return null;
        }
        return CoinTickerUtils.stringToTicker(exrParams.get(0).getAsString());
    }

    /**
     * Execute POST request with EXR coin-aware routing
     * @param endpoint The endpoint to POST to
     * @param params The parameters to POST
     * @return Response from appropriate EXR server, or null (FAIL - NO FALLBACK TO BASE_URL)
     */
    private String executeEXRPost(String endpoint, JsonObject params) {
        if (!params.has("method") || !params.has("params")) {
            return null;
        }
        String method = params.get("method").getAsString();
        JsonArray exrParams = params.getAsJsonArray("params");

        CoinTicker coin = extractCoin(exrParams);
        if (coin == null) {
            LOGGER.severe("[httpclient] Cannot route request: no coin extracted from first parameter for method " + method);
            return null; // FAIL instead of using wrong server
        }

        if (!App.exrServerPool.isCapabilitiesProbed()
                && !waitForCapabilities(HttpClientConfig.CAPABILITY_PROBE_WAIT_TIMEOUT_MS)) {
            LOGGER.warning("[httpclient] EXR capabilities not probed yet for coin: "
                    + CoinTickerUtils.tickerToString(coin));
            return null; // FAIL - NO FALLBACK TO BASE_URL
        }

        EXRServer server = App.exrServerPool.selectServerForCoin(coin);
        if (server == null) {
            LOGGER.warning("[httpclient] NO EXR SERVER SUPPORTS COIN: "
                    + CoinTickerUtils.tickerToString(coin));
            return null; // FAIL - NO FALLBACK TO BASE_URL
        }
        LOGGER.info("[httpclient] Routed " + method + " "
                + CoinTickerUtils.tickerToString(coin) + " to " + server.getEndpoint());

        List<Object> paramList = convertParams(exrParams);
        JsonElement response = server.execute(method, paramList);
        if (response == null) {
            LOGGER.warning("[httpclient] EXR request failed for " + method + " via " + server.getEndpoint());
            return null;
        }
        return normalizeEXRResponse(response);
    }

    /**
     * Normalize a parsed EXR response body into the text form consumers expect.
     * Upstream payload shapes are preserved verbatim: JSON-RPC style envelopes,
     * bare objects and bare arrays all pass through unchanged. A JSON text
     * delivered as a string-typed {@code result} member is unwrapped exactly
     * once; scalar string results (e.g. broadcast txids) are kept wrapped.
     * @param response Parsed response element
     * @return Response text or null if the element carried no content
     */
    static String normalizeEXRResponse(JsonElement response) {
        if (response == null || response.isJsonNull()) {
            return null;
        }
        if (response.isJsonObject()) {
            JsonObject obj = response.getAsJsonObject();
            if (obj.has("result") && obj.get("result").isJsonPrimitive()
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
     * Parse an upstream JSON-RPC style envelope ({@code {"result":…,"error":…}}),
     * returning null (with a logged reason) when the payload is absent, empty,
     * an error, malformed, or not an object. Consumers receive the full envelope
     * so they can read the {@code result} member exactly as with the legacy backend.
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
            LOGGER.warning("[httpclient] " + opName + " upstream error - " + obj.get("error").toString());
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
                return executeEXRPost(endpoint, params);
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
     * Returns all utxos for a list of addresses.
     * Note: This method does neither use nor update any caches!
     * 
     * @param coinTicker Fetch utxos from this coin
     * @param address    Fetch utxos from this address
     * @return JsonArray or null on error
     */
    public JsonArray getUtxosUncached(CoinTicker coinTicker, String[] addresses) {
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

        JSONObject jsonObject = null;
        JSONArray utxoArr = null;
        try {
            jsonObject = new JSONObject(res);
            utxoArr = jsonObject.getJSONArray("utxos");
        } catch (Exception e) {
            LOGGER.warning("[httpclient] getUtxosUncached " + coinInstance.getTicker() + " parse error - " + e.getMessage());
        }

        if (jsonObject == null || utxoArr == null) {
            if (jsonObject == null)
                LOGGER.warning("[httpclient] getUtxosUncached " + coinInstance.getTicker() + " null jsonObject");
            if (utxoArr == null)
                LOGGER.warning("[httpclient] getUtxosUncached " + coinInstance.getTicker() + " null utxoArr");
            return null;
        }

        JsonArray utxoList = new JsonArray();
        for (int i = 0; i < utxoArr.length(); i++) {
            JsonObject utxoJSON = new JsonObject();
            utxoJSON.addProperty("txid", utxoArr.getJSONObject(i).getString("txhash"));
            utxoJSON.addProperty("vout", utxoArr.getJSONObject(i).getInt("vout"));
            utxoJSON.addProperty("value", utxoArr.getJSONObject(i).getDouble("value"));
            utxoJSON.addProperty("spendable", true);

            String address = utxoArr.getJSONObject(i).getString("address");
            utxoJSON.addProperty("address", address);

            LegacyAddress addr = LegacyAddress.fromBase58(coinInstance.getNetworkParameters(), address);
            Script script = ScriptBuilder.createOutputScript(addr);
            utxoJSON.addProperty("scriptPubKey", new String(Hex.encode(script.getProgram())));

            int height = utxoArr.getJSONObject(i).getInt("block_number");
            int currentHeight = CoinInstance.getBlockCountByTicker(coinTicker);
            int confirmations = (currentHeight - height) + 1;
            if (height == 0)
                confirmations = 0;

            utxoJSON.addProperty("confirmations", confirmations);

            utxoList.add(utxoJSON);
        }

        return utxoList;
    }

    /**
     * Whole-coin doubles (backend JSON) to integer base units. Must round to
     * nearest: truncating the binary representation error loses satoshis
     * (e.g. 0.00050001 becomes 50000).
     */
    static long satsFromWholeCoins(double whole) {
        return Sats.fromWholeCoins(whole);
    }

    /**
     * Returns all utxos.
     * @param coinTicker Fetch utxos from this coin
     * @param expiry Time in milliseconds until cache expires
     * @return JsonArray or null on error
     */
    public JsonArray getUtxos(CoinTicker coinTicker, int expiry) {
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        long lastFetchTime = lastFetchTimes.getOrDefault(lastFetchTimesKey(coinTicker, "getUtxos"), 0L);
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFetchTime < expiry)
            return coinInstance.getAllUTXOS();

        ArrayList<String> utxoParams = coinInstance.getUTXOParams();
        if (utxoParams.size() == 0) {
            LOGGER.warning("[httpclient] getUtxos " + coinInstance.getTicker() + " null param size");
            return null;
        }

        JsonArray innerParams = new Gson().toJsonTree(utxoParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getutxos");
        params.add("params", innerParams);
        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] getUtxos " + coinInstance.getTicker() + " " + res);


        if (res == null) {
            return null;
        }

        JSONObject jsonObject = null;
        JSONArray utxoArr = null;
        try {
            jsonObject = new JSONObject(res);
            utxoArr = jsonObject.getJSONArray("utxos");
        } catch (Exception e) {
            LOGGER.warning("[httpclient] getUtxos " + coinInstance.getTicker() + " parse error - " + e.getMessage());
        }

        if (jsonObject == null || utxoArr == null) {
            if (jsonObject == null)
                LOGGER.warning("[httpclient] getUtxos " + coinInstance.getTicker() + " null jsonObject");
            if (utxoArr == null)
                LOGGER.warning("[httpclient] getUtxos " + coinInstance.getTicker() + " null utxoArr");
            return null;
        }

        List<UTXO> utxoList = new ArrayList<>();
        Set<String> returnedAddresses = new HashSet<>();
        // Backend-declared per-address failures ("errors" array the adapter
        // attaches): such addresses are treated as omitted entirely — their
        // rows are neither added below nor kept stored (see eviction).
        Set<String> failedAddresses = failedAddresses(jsonObject);
        for (int i = 0; i < utxoArr.length(); i++) {
            String rowAddress = utxoArr.getJSONObject(i).getString("address");
            if (failedAddresses.contains(rowAddress)) {
                continue;
            }
            UTXO utxo = new UTXO(coinTicker,
                    rowAddress,
                    utxoArr.getJSONObject(i).getString("txhash"),
                    utxoArr.getJSONObject(i).getInt("vout"),
                    utxoArr.getJSONObject(i).getInt("block_number"),
                    satsFromWholeCoins(utxoArr.getJSONObject(i).getDouble("value")));

            utxoList.add(utxo);
            returnedAddresses.add(rowAddress);
        }

        // Evict rows for requested-but-backend-omitted addresses. A successful
        // response that omits a tracked address (or flags it in the backend's
        // "errors" array) means the backend holds no unspent outputs for it —
        // any stored rows are confirmed-stale (a lagging view re-serves dead
        // outputs with live-looking confirmations otherwise). processUtxos
        // below only clears addresses PRESENT in the response, so without this
        // an omitted address keeps its rows forever. Runs on parsed-success
        // only: null/parse-error responses return above with stored rows
        // untouched (an outage must never wipe the wallet).
        evictOmittedAddresses(coinInstance, utxoParams, returnedAddresses);

        // Update last fetch time
        lastFetchTimes.put(lastFetchTimesKey(coinTicker, "getUtxos"), currentTime);

        coinInstance.processUtxos(utxoList);
        return coinInstance.getAllUTXOS();
    }

    /**
     * Reads the backend-declared per-address failures from a getutxos
     * response ("errors" array of {address, message} items the adapter
     * attaches; absent/null on clean responses). Never throws: an
     * unparseable array degrades to a (possibly partial) set (the omission
     * diff below still applies).
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
            LOGGER.warning("[httpclient] failedAddresses unparseable errors array - " + e.getMessage());
        }
        return failed;
    }

    /**
     * Clears stored UTXO rows for tracked addresses the backend omitted from
     * a successful getutxos response. requestedParams is the
     * [ticker, "[addr...]"] list built by CoinInstance.getUTXOParams (the
     * second element is a stringified JSONArray); returned holds the
     * addresses present in the response. Backend-declared failures never
     * reach returned (their rows are skipped before recording above), so a
     * requested address missing from returned is confirmed to hold nothing.
     * Unknown/non-tracked addresses are ignored (processUtxos
     * warns on those itself). clearUtxos spares spent-marked rows, so only
     * live-looking stale rows are dropped.
     */
    void evictOmittedAddresses(CoinInstance coinInstance, ArrayList<String> requestedParams,
            Set<String> returned) {
        Set<String> requested = new HashSet<>();
        try {
            if (requestedParams.size() > 1) {
                JSONArray arr = new JSONArray(requestedParams.get(1));
                for (int i = 0; i < arr.length(); i++) {
                    requested.add(arr.getString(i));
                }
            }
        } catch (Exception e) {
            LOGGER.warning("[httpclient] evictOmittedAddresses unparseable request params - " + e.getMessage());
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


        return parseEnvelope(res, "getRawTransaction");
    }

    public JsonObject getRawMempool(CoinTicker coinTicker, boolean verbose) {
        ArrayList<String> rawMempoolParams = new ArrayList<>();
        rawMempoolParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawMempoolParams.add(1, String.valueOf(verbose));

        JsonArray innerParams = new Gson().toJsonTree(rawMempoolParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getrawmempool");
        params.add("params", innerParams);
        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] getRawMempool " + res);


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
        if (result == null) return;

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
                LOGGER.warning("[httpclient] " + ticker + " height updates recovered after "
                        + recovered + " consecutive failures; fresh height " + blockCount);
            }

            LOGGER.finer("[httpclient] Got blockcount for currency " + ticker + " - " + blockCount);
        }
    }

    /**
     * Records one missed height update, LOUD exactly twice per episode:
     * first miss and recovery. Everything between is silent by operator
     * order — a persistent backend gap re-warning every minute is spam,
     * not signal. The -1112s in consumer logs are the visible symptom
     * while a coin is unavailable.
     */
    private static void noteHeightMiss(CoinInstance coinInstance, String reason) {
        String ticker = CoinTickerUtils.tickerToString(coinInstance.getTicker());
        int streak = coinInstance.incrementUpdateFailures();
        if (streak != 1) {
            return;
        }
        long ageSec = coinInstance.getLastBlockCountMillis() <= 0 ? -1
                : (System.currentTimeMillis() - coinInstance.getLastBlockCountMillis()) / 1000;
        String lastGood = ageSec < 0 ? "no height ever received"
                : "last-good height age " + ageSec + "s";
        LOGGER.warning("[httpclient] " + ticker + " height update failed (" + reason
                + "); streak 1, serving stale height, " + lastGood);
    }

    public JsonObject getBlock(CoinTicker coinTicker, String hash, boolean verbose) {
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


        return parseEnvelope(res, "getBlock");
    }

    public JsonObject getBlockHash(CoinTicker coinTicker, int height) {
        JsonArray innerParams = new JsonArray();
        innerParams.add(CoinTickerUtils.tickerToString(coinTicker));
        innerParams.add(height);

        JsonObject params = new JsonObject();
        params.addProperty("method", "getblockhash");
        params.add("params", innerParams);
        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] getBlockHash " + res);


        return parseEnvelope(res, "getBlockHash");
    }

    public JsonObject getTransaction(CoinTicker coinTicker, String txid, boolean verbose) {
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
            LOGGER.warning("[httpclient] sendRawTransaction invalid upstream JSON - " + e.getMessage());
            return null;
        }
    }

    /**
     * Return all transactions associated with the coin.
     * @param coinTicker Coin
     * @param startTime Beginning of the time frame in unix time
     * @param endTime End of the time frame in unix time
     * @param expiry Time in milliseconds until cache expires
     * @return JsonArray or null on error
     */
    public JsonArray getHistory(CoinTicker coinTicker, int startTime, int endTime, int expiry) {
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        long lastFetchTime = lastFetchTimes.getOrDefault(lastFetchTimesKey(coinTicker, "getHistory"), 0L);
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFetchTime < expiry)
            return filterHistory(coinInstance.getAllTransactions(), startTime, endTime);

        ArrayList<String> utxoParams = coinInstance.getUTXOParams();
        if (utxoParams.size() == 0) {
            LOGGER.warning("[httpclient] getHistory " + coinInstance.getTicker() + " null param size");
            return null;
        }

        JsonArray innerParams = new Gson().toJsonTree(utxoParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "gethistory");
        params.add("params", innerParams);

        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] getHistory " + coinInstance.getTicker() + " " + res);
        if (res == null) {
            return null;
        }

        JsonArray json;
        try {
            json = new Gson().fromJson(res, JsonArray.class);
        } catch (Exception e) {
            LOGGER.warning("[httpclient] getHistory parsing error - Response: " + res + " - " + e.getMessage());
            return null;
        }

        if (json == null) {
            LOGGER.warning("[httpclient] getHistory " + coinInstance.getTicker() + " null json");
            return null;
        }

        List<Transaction> historyList = new ArrayList<>();
        for (JsonElement elements : json) {
            for (JsonElement element : elements.getAsJsonArray()) {
                //LOGGER.warning("*** DEBUG *** [httpclient] getHistory " + element);
                JsonObject jsonObject = element.getAsJsonObject();

                List<String> fromAddresses = new Gson().fromJson(jsonObject.get("from_addresses"), new TypeToken<List<String>>() {
                }.getType());

                Transaction tx = new Transaction(coinTicker,
                        jsonObject.get("address").getAsString(),
                        jsonObject.get("txid").getAsString(),
                        jsonObject.get("blockhash").getAsString(),
                        jsonObject.get("vout").getAsInt(),
                        jsonObject.get("amount").getAsDouble(),
                        jsonObject.get("confirmations").getAsInt(),
                        jsonObject.get("blocktime").getAsInt(),
                        fromAddresses);
                tx.setCategory(jsonObject.get("category").getAsString());
                tx.setFee(jsonObject.get("fee").getAsDouble());

                historyList.add(tx);
            }
        }
        coinInstance.processHistoryTxs(historyList);

        // Return the latest transaction history
        JsonArray txs = coinInstance.getAllTransactions();
        if (txs == null) {
            LOGGER.warning("[httpclient] getHistory " + coinInstance.getTicker() + " null txs");
            return null;
        }

        // Update last fetch time
        lastFetchTimes.put(lastFetchTimesKey(coinTicker, "getHistory"), currentTime);

        // Filter txs by time if time frame requested
        return filterHistory(txs, startTime, endTime);
    }

    /**
     * Return all transaction hashes associated with the coin.
     * @param coinTicker Coin
     * @param startTime Beginning of the time frame in unix time
     * @param endTime End of the time frame in unix time
     * @param expiry Time in milliseconds until cache expires
     * @return JsonArray or null on error
     */
    public JsonArray getTransactionHistory(CoinTicker coinTicker, int startTime, int endTime, int expiry) {
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        long lastFetchTime = lastFetchTimes.getOrDefault(lastFetchTimesKey(coinTicker, "getAddressHistory"), 0L);
        long currentTime = System.currentTimeMillis() / 1000;
        if ((currentTime - lastFetchTime) < expiry)
            return filterHistory(coinInstance.getAllTransactions(), startTime, endTime);

        ArrayList<String> utxoParams = coinInstance.getUTXOParams();
        if (utxoParams.size() == 0) {
            LOGGER.warning("[httpclient] getAddressHistory " + coinInstance.getTicker() + " null param size");
            return null;
        }

        JsonArray innerParams = new Gson().toJsonTree(utxoParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getaddresshistory");
        params.add("params", innerParams);

        String res = executeRequest("/", params);
        LOGGER.finer("[httpclient] getAddressHistory " + coinInstance.getTicker() + " " + res);
        if (res == null) {
            return null;
        }

        JsonArray json;
        try {
            json = new Gson().fromJson(res, JsonArray.class);
        } catch (Exception e) {
            LOGGER.warning("[httpclient] getAddressHistory parsing error - Response: " + res + " - " + e.getMessage());
            return null;
        }
        if (json == null) {
            LOGGER.warning("[httpclient] getAddressHistory " + coinInstance.getTicker() + " null json");
            return null;
        }

        List<Transaction> historyList = new ArrayList<>();
        for (JsonElement elements : json) {
            for (JsonElement element : elements.getAsJsonArray()) {
                //LOGGER.warning("*** DEBUG *** [httpclient] getAddressHistory " + element );
                JsonObject jsonObject = element.getAsJsonObject();

                String txid = jsonObject.get("tx_hash").getAsString();
                JsonObject rawTransaction = null;

                int fails = 1;
                while (fails > 0) {
                    if (fails >= 5)
                        fails = 0;

                    try {
                        rawTransaction = getRawTransaction(coinTicker, txid, true);

                        if (rawTransaction != null && !rawTransaction.get("result").isJsonNull()) {
                            rawTransaction = rawTransaction.getAsJsonObject("result");
                            fails = 0;

                            break;
                        } else
                            ++fails;
                    } catch (Exception e) {
                        LOGGER.warning("[httpclient] getRawTransaction failed - " + e.getMessage());
                        ++fails;
                    }
                }

                if (fails != 0)
                    continue;

                for (JsonElement vin : rawTransaction.get("vin").getAsJsonArray()) {
                    String vinTxid = vin.getAsJsonObject().get("txid").getAsString();
                    int voutInt = vin.getAsJsonObject().get("vout").getAsInt();

                    JsonObject voutRawTransaction = null;

                    fails = 1;
                    while (fails > 0) {
                        if (fails >= 5)
                            fails = 0;

                        try {
                            voutRawTransaction = getRawTransaction(coinTicker, vinTxid, true);

                            if (voutRawTransaction != null && !voutRawTransaction.get("result").isJsonNull()) {
                                voutRawTransaction = voutRawTransaction.getAsJsonObject("result");
                                fails = 0;

                                break;
                            } else
                                ++fails;
                        } catch (Exception e) {
                            LOGGER.warning("[httpclient] getRawTransaction(vout) failed - " + e.getMessage());
                            ++fails;
                        }
                    }

                    if (fails != 0)
                        continue;

                    JsonObject vout = voutRawTransaction.get("vout").getAsJsonArray().get(voutInt).getAsJsonObject();
                    JsonObject scriptPubKey = vout.getAsJsonObject("scriptPubKey");

                    if ((scriptPubKey == null || scriptPubKey.isJsonNull()) || scriptPubKey.get("addresses").isJsonNull())
                        continue;

                    for (JsonElement addressElement : scriptPubKey.getAsJsonArray("addresses")) {
                        String address = addressElement.getAsString();

                        for (AddressBalance addressBalance : coinInstance.getAddressKeyPairs()) {
                            String utxoAddress = addressBalance.getAddress().toBase58();

                            if (utxoAddress.equals(address)) {
                                List<String> fromAddresses = new ArrayList<>();

                                Transaction tx = new Transaction(coinTicker,
                                        address,
                                        txid,
                                        rawTransaction.get("blockhash").getAsString(),
                                        voutInt,
                                        vout.get("value").getAsDouble(),
                                        rawTransaction.get("confirmations").getAsInt(),
                                        rawTransaction.get("blocktime").getAsInt(),
                                        fromAddresses);
                                tx.setCategory("send");
                                tx.setFee(0.0);

                                historyList.add(tx);
                            }
                        }
                    }
                }


                for (JsonElement vout : rawTransaction.get("vout").getAsJsonArray()) {
                    JsonObject scriptPubKey = vout.getAsJsonObject().getAsJsonObject("scriptPubKey");

                    try {
                        if (scriptPubKey.isJsonNull() || scriptPubKey.get("addresses").isJsonNull())
                            continue;
                    } catch (Exception e) {
                        continue;
                    }

                    for (JsonElement addressElement : scriptPubKey.getAsJsonArray("addresses")) {
                        String address = addressElement.getAsString();

                        for (AddressBalance addressBalance : coinInstance.getAddressKeyPairs()) {
                            String utxoAddress = addressBalance.getAddress().toBase58();

                            if (utxoAddress.equals(address)) {
                                List<String> fromAddresses = new ArrayList<>();

                                Transaction tx = new Transaction(coinTicker,
                                        address,
                                        txid,
                                        rawTransaction.get("blockhash").getAsString(),
                                        vout.getAsJsonObject().get("n").getAsInt(),
                                        vout.getAsJsonObject().get("value").getAsDouble(),
                                        rawTransaction.get("confirmations").getAsInt(),
                                        rawTransaction.get("blocktime").getAsInt(),
                                        fromAddresses);
                                tx.setCategory("receive");
                                tx.setFee(0.0);

                                historyList.add(tx);
                            }
                        }
                    }
                }
            }
        }
        coinInstance.processHistoryTxs(historyList);

        // Return the latest transaction history
        JsonArray txs = coinInstance.getAllTransactions();
        if (txs == null) {
            LOGGER.warning("[httpclient] getAddressHistory " + coinInstance.getTicker() + " null txs");
            return null;
        }

        // Update last fetch time
        lastFetchTimes.put(lastFetchTimesKey(coinTicker, "getAddressHistory"), currentTime);

        // Filter txs by time if time frame requested
        return filterHistory(txs, startTime, endTime);
    }

    /**
     * Filters the transaction array in place. This does not make a copy but modifies
     * the existing list.
     * @param txs List to filter
     * @param startTime Transaction on or after this time
     * @param endTime Transaction on or before this time
     * @return Filtered transaction list
     */
    private JsonArray filterHistory(JsonArray txs, int startTime, int endTime) {
        if (endTime <= 0)
            return txs;
        Iterator<JsonElement> it = txs.iterator();
        while (it.hasNext()) {
            JsonObject tx = it.next().getAsJsonObject();
            int txTime = tx.get("time").getAsInt();
            if (txTime < startTime || txTime > endTime)
                it.remove();
        }
        return txs;
    }

    /**
     * Returns the key used with last fetch times.
     * @param ticker Coin
     * @param method Storage key name
     * @return Storage key
     */
    private String lastFetchTimesKey(CoinTicker ticker, String method) {
        return method + ":" + ticker.name();
    }
}
