package io.cloudchains.app.net.api.http.client;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.App;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.util.AddressBalance;
import io.cloudchains.app.util.UTXO;
import io.cloudchains.app.util.history.Transaction;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HTTPClient {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private CloseableHttpClient client;
    private ConcurrentHashMap<String, Long> lastFetchTimes;
    private int logCount = 0;

    // Enhanced error handling structure
    private static class HTTPError {
        private final String method;
        private final String endpoint;
        private final Exception exception;
        private final int statusCode;
        private final String details;
        
        public HTTPError(String method, String endpoint, Exception exception, int statusCode, String details) {
            this.method = method;
            this.endpoint = endpoint;
            this.exception = exception;
            this.statusCode = statusCode;
            this.details = details;
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP Error [").append(method).append(" ").append(endpoint).append("]");
            if (statusCode > 0) sb.append(" Status: ").append(statusCode);
            if (details != null) sb.append(" Details: ").append(details);
            if (exception != null) sb.append(" Exception: ").append(exception.getMessage());
            return sb.toString();
        }
    }

    public HTTPClient(int maximumSockets) {
        SSLContext sslContext = null;
        lastFetchTimes = new ConcurrentHashMap<>();

        try {
            sslContext = new SSLContextBuilder()
                    .loadTrustMaterial(null, (x509CertChain, authType) -> true)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
            logError("INIT", "SSL_CONTEXT", e, 0, "Failed to create SSL context");
        }

        Header header = new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        List<Header> headers = Lists.newArrayList(header);

        RequestConfig.Builder requestBuilder = RequestConfig.custom();
        requestBuilder.setConnectTimeout(30000);
        requestBuilder.setConnectionRequestTimeout(30000);
        requestBuilder.setSocketTimeout(30000);

        if (sslContext == null) {
            logError("INIT", "HTTP_CLIENT", new RuntimeException("SSL context is null"), 0, "Using HTTP only");
        }
        
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(
                RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("http", PlainConnectionSocketFactory.INSTANCE)
                        .register("https", new SSLConnectionSocketFactory(sslContext,
                                NoopHostnameVerifier.INSTANCE))
                        .build()
        );
        connectionManager.setDefaultMaxPerRoute(maximumSockets);
        connectionManager.setMaxTotal(maximumSockets);

        client = HttpClients.custom()
                .setDefaultHeaders(headers)
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .setSSLContext(sslContext)
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestBuilder.build())
                .build();
    }

    public void close() {
        try {
            if (client != null) {
                client.close();
            }
        } catch (IOException e) {
            logError("CLOSE", "HTTP_CLIENT", e, 0, "Failed to close HTTP client");
        }
    }

    private String doGet(String endpoint) {
        String method = "GET";
        HttpGet httpget = new HttpGet(App.BASE_URL + endpoint);
        CloseableHttpResponse response = null;
        
        try {
            response = client.execute(httpget);
            
            if (!validateResponse(response, method, endpoint)) {
                return null;
            }
            
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity);
            EntityUtils.consume(entity);
            return result;
            
        } catch (SocketTimeoutException e) {
            logError(method, endpoint, e, 0, "Request timeout");
        } catch (UnknownHostException e) {
            logError(method, endpoint, e, 0, "Unknown host");
        } catch (org.apache.http.client.ClientProtocolException e) {
            logError(method, endpoint, e, 0, "Protocol error");
        } catch (IOException e) {
            logError(method, endpoint, e, 0, "IO error: " + e.getMessage());
        } catch (Exception e) {
            logError(method, endpoint, e, 0, "Unexpected error: " + e.getMessage());
        } finally {
            httpget.reset();
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    logError(method, endpoint, e, 0, "Failed to close response");
                }
            }
        }
        
        return null;
    }

    private String doPost(String endpoint, JsonObject params) {
        String method = "POST";
        HttpPost httpPost = new HttpPost();
        httpPost.setURI(URI.create(App.BASE_URL + endpoint));
        
        try {
            httpPost.setEntity(new StringEntity(params.toString()));
        } catch (UnsupportedEncodingException e) {
            logError(method, endpoint, e, 0, "Failed to create request entity");
            httpPost.reset();
            return null;
        }
        
        CloseableHttpResponse response = null;
        try {
            response = client.execute(httpPost);
            
            if (!validateResponse(response, method, endpoint)) {
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    String errorBody = EntityUtils.toString(entity);
                    logError(method, endpoint, null, response.getStatusLine().getStatusCode(), 
                            "Bad response body: " + errorBody);
                }
                return null;
            }
            
            HttpEntity entity = response.getEntity();
            String result = EntityUtils.toString(entity);
            EntityUtils.consume(entity);
            return result;
            
        } catch (SocketTimeoutException e) {
            logError(method, endpoint, e, 0, "Request timeout");
        } catch (UnknownHostException e) {
            logError(method, endpoint, e, 0, "Unknown host");
        } catch (org.apache.http.client.ClientProtocolException e) {
            logError(method, endpoint, e, 0, "Protocol error");
        } catch (IOException e) {
            logError(method, endpoint, e, 0, "IO error: " + e.getMessage());
        } catch (Exception e) {
            logError(method, endpoint, e, 0, "Unexpected error: " + e.getMessage());
        } finally {
            httpPost.reset();
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    logError(method, endpoint, e, 0, "Failed to close response");
                }
            }
        }
        
        return null;
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

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getUtxosUncached " + coinInstance.getTicker() + " " + res);

        if (res == null) {
            logWarning("getUtxosUncached", coinInstance.getTicker().toString(), "null post result");
            return null;
        }

        JSONObject jsonObject = parseJsonObject(res, "getutxosUncached", "/");
        JSONArray utxoArr = parseJsonArray(jsonObject, "utxos", "getutxosUncached", "/");
        
        if (jsonObject == null || utxoArr == null) {
            if (jsonObject == null)
                logWarning("getUtxosUncached", coinInstance.getTicker().toString(), "null jsonObject");
            if (utxoArr == null)
                logWarning("getUtxosUncached", coinInstance.getTicker().toString(), "null utxoArr");
            return null;
        }

        JsonArray utxoList = new JsonArray();
        for (int i = 0; i < utxoArr.length(); i++) {
            try {
                JsonObject utxoJSON = new JsonObject();
                utxoJSON.addProperty("txid", utxoArr.getJSONObject(i).getString("txhash"));
                utxoJSON.addProperty("vout", utxoArr.getJSONObject(i).getInt("vout"));
                utxoJSON.addProperty("value", utxoArr.getJSONObject(i).getDouble("value"));
                utxoJSON.addProperty("spendable", true);

                String address = utxoArr.getJSONObject(i).getString("address");
                utxoJSON.addProperty("address", address);

                Address addr = LegacyAddress.fromBase58(coinInstance.getNetworkParameters(), address);
                Script script = ScriptBuilder.createOutputScript(addr);
                utxoJSON.addProperty("scriptPubKey", new String(Hex.encode(script.getProgram())));

                int height = utxoArr.getJSONObject(i).getInt("block_number");
                int currentHeight = CoinInstance.getBlockCountByTicker(coinTicker);
                int confirmations = (currentHeight - height) + 1;
                if (height == 0)
                    confirmations = 0;

                utxoJSON.addProperty("confirmations", confirmations);

                utxoList.add(utxoJSON);
            } catch (Exception e) {
                logError("getUtxosUncached", "/", e, 0, "Failed to process UTXO at index " + i);
            }
        }

        return utxoList;
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
            logWarning("getUtxos", coinInstance.getTicker().toString(), "null param size");
            return null;
        }

        JsonArray innerParams = new Gson().toJsonTree(utxoParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getutxos");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getUtxos " + coinInstance.getTicker() + " " + res);

        if (res == null) {
            logWarning("getUtxos", coinInstance.getTicker().toString(), "null post result");
            return null;
        }

        JSONObject jsonObject = parseJsonObject(res, "getUtxos", "/");
        JSONArray utxoArr = parseJsonArray(jsonObject, "utxos", "getUtxos", "/");
        
        if (jsonObject == null || utxoArr == null) {
            if (jsonObject == null)
                logWarning("getUtxos", coinInstance.getTicker().toString(), "null jsonObject");
            if (utxoArr == null)
                logWarning("getUtxos", coinInstance.getTicker().toString(), "null utxoArr");
            return null;
        }

        List<UTXO> utxoList = new ArrayList<>();
        for (int i = 0; i < utxoArr.length(); i++) {
            try {
                UTXO utxo = new UTXO(coinTicker,
                        utxoArr.getJSONObject(i).getString("address"),
                        utxoArr.getJSONObject(i).getString("txhash"),
                        utxoArr.getJSONObject(i).getInt("vout"),
                        utxoArr.getJSONObject(i).getInt("block_number"),
                        (long) Math.floor(utxoArr.getJSONObject(i).getDouble("value") * 100000000.0));

                utxoList.add(utxo);
            } catch (Exception e) {
                logError("getUtxos", "/", e, 0, "Failed to create UTXO at index " + i);
            }
        }

        // Update last fetch time
        lastFetchTimes.put(lastFetchTimesKey(coinTicker, "getUtxos"), currentTime);

        coinInstance.processUtxos(utxoList);
        return coinInstance.getAllUTXOS();
    }

    public void getAllFees() {
        String res = doGet("/fees");

        if (res == null) return;

        JSONObject jsonObject = parseJsonObject(res, "getAllFees", "/fees");
        if (jsonObject == null) {
            logError("getAllFees", "/fees", null, 0, "Failed to parse response");
            return;
        }
        
        JSONObject fees = parseJsonObject(jsonObject, "result", "getAllFees", "/fees");
        if (fees == null) {
            logError("getAllFees", "/fees", null, 0, "No result object in response");
            return;
        }

        for (CoinTicker coinTicker : CoinTicker.coins()) {
            CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
            String ticker = CoinTickerUtils.tickerToString(coinTicker);

            try {
                if (!fees.has(ticker) || fees.isNull(ticker)) {
                    coinInstance.incrementUpdateFailures();
                    continue;
                }

                double fee = fees.getDouble(ticker);

                coinInstance.addRelayFee(coinTicker, fee);

                if (logCount % 30 == 0)
                    LOGGER.log(Level.INFO, "[httpclient] Got relayfee for currency " + ticker + " - " + fee);
                else
                    LOGGER.log(Level.FINER, "[httpclient] Got relayfee for currency " + ticker + " - " + fee);
            } catch (Exception e) {
                logError("getAllFees", "/fees", e, 0, "Failed to process fee for " + ticker);
                coinInstance.incrementUpdateFailures();
            }
        }
        logCount += 1;
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

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getRawTransaction " + res);

        if (res == null) return null;

        try {
            return new Gson().fromJson(res, JsonObject.class);
        } catch (Exception e) {
            logError("getRawTransaction", "/", e, 0, "Failed to parse response");
            return null;
        }
    }

    public JsonObject getRawMempool(CoinTicker coinTicker, boolean verbose) {
        ArrayList<String> rawMempoolParams = new ArrayList<>();
        rawMempoolParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawMempoolParams.add(1, String.valueOf(verbose));

        JsonArray innerParams = new Gson().toJsonTree(rawMempoolParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getrawmempool");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getRawMempool " + res);

        if (res == null) return null;

        try {
            return new Gson().fromJson(res, JsonObject.class);
        } catch (Exception e) {
            logError("getRawMempool", "/", e, 0, "Failed to parse response");
            return null;
        }
    }

    public void getBlockCount(CoinTicker coinTicker) {
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        ArrayList<String> blockCountParams = new ArrayList<>();
        blockCountParams.add(0, CoinTickerUtils.tickerToString(coinTicker));

        JsonArray innerParams = new Gson().toJsonTree(blockCountParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getblockcount");
        params.add("params", innerParams);

        String res = doPost("/", params);

        if (res == null) return;

        try {
            JSONObject jsonObject = parseJsonObject(res, "getBlockCount", "/");
            JSONObject result = parseJsonObject(jsonObject, "result", "getBlockCount", "/");
            if (result == null) {
                logError("getBlockCount", "/", null, 0, "No result object in response");
                return;
            }
            
            int blockCount = result.getInt("result");
            coinInstance.addBlockCount(coinTicker, blockCount);
            LOGGER.log(Level.FINER, "[httpclient] Got blockcount for currency " + coinTicker + " - " + blockCount);
        } catch (Exception e) {
            logError("getBlockCount", "/", e, 0, "Failed to process block count");
        }
    }

    public void getAllBlockCounts() {
        String res = doGet("/height");

        if (res == null) return;

        JSONObject jsonObject = parseJsonObject(res, "getAllBlockCounts", "/height");
        if (jsonObject == null) {
            logError("getAllBlockCounts", "/height", null, 0, "Failed to parse response");
            return;
        }
        
        JSONObject blockCounts = parseJsonObject(jsonObject, "result", "getAllBlockCounts", "/height");
        if (blockCounts == null) {
            logError("getAllBlockCounts", "/height", null, 0, "No result object in response");
            return;
        }

        for (CoinTicker coinTicker : CoinTicker.coins()) {
            CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
            String ticker = CoinTickerUtils.tickerToString(coinTicker);

            try {
                if (!blockCounts.has(ticker) || blockCounts.isNull(ticker)) {
                    coinInstance.incrementUpdateFailures();
                    continue;
                }

                int blockCount = blockCounts.getInt(ticker);

                coinInstance.addBlockCount(coinTicker, blockCount);
                coinInstance.resetUpdateFailures();

                LOGGER.log(Level.FINER, "[httpclient] Got blockcount for currency " + ticker + " - " + blockCount);
            } catch (Exception e) {
                logError("getAllBlockCounts", "/height", e, 0, "Failed to process block count for " + ticker);
                coinInstance.incrementUpdateFailures();
            }
        }
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

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getBlock " + res);

        if (res == null) return null;

        try {
            return new Gson().fromJson(res, JsonObject.class);
        } catch (Exception e) {
            logError("getBlock", "/", e, 0, "Failed to parse response");
            return null;
        }
    }

    public JsonObject getBlockHash(CoinTicker coinTicker, int height) {
        JsonArray innerParams = new JsonArray();
        innerParams.add(CoinTickerUtils.tickerToString(coinTicker));
        innerParams.add(height);

        JsonObject params = new JsonObject();
        params.addProperty("method", "getblockhash");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getBlockHash " + res);

        if (res == null) return null;

        try {
            return new Gson().fromJson(res, JsonObject.class);
        } catch (Exception e) {
            logError("getBlockHash", "/", e, 0, "Failed to parse response");
            return null;
        }
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

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getTransaction " + res);

        if (res == null) return null;

        try {
            return new Gson().fromJson(res, JsonObject.class);
        } catch (Exception e) {
            logError("getTransaction", "/", e, 0, "Failed to parse response");
            return null;
        }
    }

    public JsonObject sendRawTransaction(CoinTicker coinTicker, String rawTx) {
        ArrayList<String> rawTxParams = new ArrayList<>();
        rawTxParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawTxParams.add(1, rawTx);

        JsonArray innerParams = new Gson().toJsonTree(rawTxParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "sendrawtransaction");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] sendRawTransaction " + res);

        if (res == null) return null;

        try {
            return new Gson().fromJson(res, JsonObject.class);
        } catch (Exception e) {
            logError("sendRawTransaction", "/", e, 0, "Failed to parse response");
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
            logWarning("getHistory", coinInstance.getTicker().toString(), "null param size");
            return null;
        }

        JsonArray innerParams = new Gson().toJsonTree(utxoParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "gethistory");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getHistory " + coinInstance.getTicker() + " " + res);
        if (res == null) {
            logWarning("getHistory", coinInstance.getTicker().toString(), "null post result");
            return null;
        }

        JsonArray json = null;
        try {
            json = new Gson().fromJson(res, JsonArray.class);
        } catch (Exception e) {
            logError("getHistory", "/", e, 0, "Failed to parse JSON array");
            return null;
        }
        
        if (json == null) {
            logError("getHistory", "/", null, 0, "null json");
            return null;
        }

        List<Transaction> historyList = new ArrayList<>();
        for (JsonElement elements : json) {
            try {
                for (JsonElement element : elements.getAsJsonArray()) {
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
            } catch (Exception e) {
                logError("getHistory", "/", e, 0, "Failed to process history element");
            }
        }
        coinInstance.processHistoryTxs(historyList);

        // Return the latest transaction history
        JsonArray txs = coinInstance.getAllTransactions();
        if (txs == null) {
            logWarning("getHistory", coinInstance.getTicker().toString(), "null txs");
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
            logWarning("getAddressHistory", coinInstance.getTicker().toString(), "null param size");
            return null;
        }

        JsonArray innerParams = new Gson().toJsonTree(utxoParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getaddresshistory");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getAddressHistory " + coinInstance.getTicker() + " " + res);
        if (res == null) {
            logWarning("getAddressHistory", coinInstance.getTicker().toString(), "null post result");
            return null;
        }

        JsonArray json = null;
        try {
            json = new Gson().fromJson(res, JsonArray.class);
        } catch (Exception e) {
            logError("getAddressHistory", "/", e, 0, "Failed to parse JSON array");
            return null;
        }
        
        if (json == null) {
            logError("getAddressHistory", "/", null, 0, "null json");
            return null;
        }

        List<Transaction> historyList = new ArrayList<>();
        for (JsonElement elements : json) {
            try {
                for (JsonElement element : elements.getAsJsonArray()) {
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
                            logError("getAddressHistory", "/", e, 0, "Failed to get raw transaction for " + txid);
                            ++fails;
                        }
                    }

                    if (fails != 0)
                        continue;

                    for (JsonElement vin : rawTransaction.get("vin").getAsJsonArray()) {
                        try {
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
                                    logError("getAddressHistory", "/", e, 0, "Failed to get vout raw transaction for " + vinTxid);
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
                                    String utxoAddress = addressBalance.getAddress().toString();

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
                        } catch (Exception e) {
                            logError("getAddressHistory", "/", e, 0, "Failed to process vin");
                        }
                    }


                    for (JsonElement vout : rawTransaction.get("vout").getAsJsonArray()) {
                        try {
                            JsonObject scriptPubKey = vout.getAsJsonObject().getAsJsonObject("scriptPubKey");

                            if (scriptPubKey == null || scriptPubKey.isJsonNull() || 
                                !scriptPubKey.has("addresses") || scriptPubKey.get("addresses").isJsonNull())
                                continue;

                            for (JsonElement addressElement : scriptPubKey.getAsJsonArray("addresses")) {
                                String address = addressElement.getAsString();

                                for (AddressBalance addressBalance : coinInstance.getAddressKeyPairs()) {
                                    String utxoAddress = addressBalance.getAddress().toString();

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
                        } catch (Exception e) {
                            logError("getAddressHistory", "/", e, 0, "Failed to process vout");
                        }
                    }
                }
            } catch (Exception e) {
                logError("getAddressHistory", "/", e, 0, "Failed to process transaction history element");
            }
        }
        coinInstance.processHistoryTxs(historyList);

        // Return the latest transaction history
        JsonArray txs = coinInstance.getAllTransactions();
        if (txs == null) {
            logWarning("getAddressHistory", coinInstance.getTicker().toString(), "null txs");
            return null;
        }

        // Update last fetch time
        lastFetchTimes.put(lastFetchTimesKey(coinTicker, "getAddressHistory"), currentTime);

        // Filter txs by time if time frame requested
        return filterHistory(txs, startTime, endTime);
    }

    // Enhanced validation with structured error logging
    private boolean validateResponse(HttpResponse response, String method, String endpoint) {
        if (response == null) {
            logError(method, endpoint, null, 0, "Response is null");
            return false;
        }
        
        int statusCode = response.getStatusLine().getStatusCode();
        long contentLength = response.getEntity() != null ? response.getEntity().getContentLength() : -1;
        
        if (statusCode != 200) {
            logError(method, endpoint, null, statusCode, "HTTP status code: " + statusCode);
            return false;
        }
        
        if (contentLength == 0) {
            logError(method, endpoint, null, statusCode, "Empty response body");
            return false;
        }
        
        return true;
    }

    // Keep original validateResponse method for backward compatibility
    private boolean validateResponse(HttpResponse response) {
        return validateResponse(response, "UNKNOWN", "/");
    }

    // Enhanced logError method
    private void logError(String method, String endpoint, Exception exception, int statusCode, String details) {
        HTTPError error = new HTTPError(method, endpoint, exception, statusCode, details);
        LOGGER.log(Level.SEVERE, "[httpclient] " + error.toString());
    }

    // Enhanced logWarning method
    private void logWarning(String method, String endpoint, String message) {
        LOGGER.log(Level.WARNING, "[httpclient] " + method + " " + endpoint + " " + message);
    }

    // Enhanced JSON parsing methods
    private JSONObject parseJsonObject(String response, String method, String endpoint) {
        if (response == null || response.trim().isEmpty()) {
            logError(method, endpoint, null, 0, "Empty response");
            return null;
        }
        
        try {
            return new JSONObject(response);
        } catch (Exception e) {
            logError(method, endpoint, e, 0, "Failed to parse JSON response");
            return null;
        }
    }

    private JSONObject parseJsonObject(JSONObject jsonObject, String key, String method, String endpoint) {
        if (jsonObject == null) {
            logError(method, endpoint, null, 0, "JSON object is null");
            return null;
        }
        
        try {
            return jsonObject.getJSONObject(key);
        } catch (Exception e) {
            logError(method, endpoint, e, 0, "Failed to get JSON object: " + key);
            return null;
        }
    }

    private JSONArray parseJsonArray(JSONObject jsonObject, String key, String method, String endpoint) {
        if (jsonObject == null) {
            logError(method, endpoint, null, 0, "JSON object is null");
            return null;
        }
        
        try {
            return jsonObject.getJSONArray(key);
        } catch (Exception e) {
            logError(method, endpoint, e, 0, "Failed to get JSON array: " + key);
            return null;
        }
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
        try {
            Iterator<JsonElement> it = txs.iterator();
            while (it.hasNext()) {
                try {
                    JsonObject tx = it.next().getAsJsonObject();
                    int txTime = tx.get("time").getAsInt();
                    if (txTime < startTime || txTime > endTime)
                        it.remove();
                } catch (Exception e) {
                    logError("filterHistory", "MEMORY", e, 0, "Failed to process transaction during filtering");
                    it.remove();
                }
            }
        } catch (Exception e) {
            logError("filterHistory", "MEMORY", e, 0, "Failed to filter history");
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
