package io.xlite.daemon.app.net.api.http.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class EXRWrapper {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    private final String exrEndpoint;
    private final CloseableHttpClient client;
    private final Gson gson;
    // Constants for configuration
    private static final String LOG_TAG = HttpClientConfig.LOG_TAG;

    public EXRWrapper(String exrEndpoint) {
        this.exrEndpoint = exrEndpoint;
        this.gson = new Gson();
        // Configure HTTP client with timeouts
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(HttpClientConfig.HTTP_TIMEOUT_MS)
                .setConnectionRequestTimeout(HttpClientConfig.HTTP_TIMEOUT_MS)
                .setSocketTimeout(HttpClientConfig.HTTP_TIMEOUT_MS)
                .build();
        this.client = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .build();
    }

    /**
     * Execute an HTTP request and return the response body.
     * Uses the same resource cleanup pattern as HTTPClient.executeHttpRequest()
     * @param request The HTTP request to execute
     * @param operation Description of the operation for logging
     * @return Response body string or null on error
     */
    private String executeHttpRequest(HttpRequestBase request, String operation) {
        return HttpUtils.executeHttpRequest(client, request, operation);
    }

    /**
     * Parse a response body verbatim. No synthetic wrapping is applied:
     * arrays and primitives are returned as-is so upstream payload shapes
     * survive unchanged (callers normalize where needed).
     * @param responseBody The raw response body
     * @return Parsed JsonElement or null if the body was not valid JSON
     */
    private JsonElement parseBody(String responseBody) {
        try {
            return gson.fromJson(responseBody, JsonElement.class);
        } catch (JsonSyntaxException e) {
            LOGGER.warning(LOG_TAG + " invalid JSON response - " + e.getMessage());
            return null;
        }
    }

    /**
     * Execute a POST request to an EXR endpoint.
     * Transforms the method and params into EXR format.
     *
     * @param method The method name (e.g., "getblockhash")
     * @param params The parameters as a List of Objects
     * @return Parsed response element or null on error
     */
    public JsonElement execute(String method, List<Object> params) {
        String endpoint = exrEndpoint + "/xrs/" + method;
        String currency = params.isEmpty() || !(params.get(0) instanceof String) ?
                "unknown" : (String) params.get(0);
        String requestBody = gson.toJson(params);
        HttpPost httpPost = new HttpPost();
        httpPost.setURI(URI.create(endpoint));
        httpPost.setHeader("Content-Type", "application/json");
        try {
            httpPost.setEntity(new StringEntity(requestBody));
            String responseBody = executeHttpRequest(httpPost, "execute POST for " + method + " " + currency);
            return responseBody != null ? parseBody(responseBody) : null;
        } catch (IOException e) {
            LOGGER.warning(LOG_TAG + " execute POST failed for " + method + " " + currency + " endpoint: " + endpoint + ", " + e.getMessage());
            return null;
        } finally {
            httpPost.reset();
        }
    }

    /**
     * Execute a GET request to an EXR endpoint.
     *
     * @param method The method name (e.g., "fees", "heights")
     * @return Parsed response element or null on error
     */
    public JsonElement executeGet(String method) {
        String endpoint = exrEndpoint + "/xrs/" + method;
        HttpGet httpGet = new HttpGet(endpoint);
        httpGet.setHeader("Content-Type", "application/json");
        String responseBody = executeHttpRequest(httpGet, "execute GET for method " + method);
        return responseBody != null ? parseBody(responseBody) : null;
    }

    /**
     * Close the HTTP client resources.
     */
    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            LOGGER.warning(LOG_TAG + " Failed to close HTTP client" + e.getMessage());
        }
    }

}