package io.cloudchains.app.net.api.http.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class EXRWrapper {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    private final String exrEndpoint;
    private final CloseableHttpClient client;
    private final Gson gson;
    // Constants for configuration
    private static final int HTTP_TIMEOUT_MS = 30000;
    private static final String LOG_TAG = "[exr]";

    public EXRWrapper(String exrEndpoint) {
        this.exrEndpoint = exrEndpoint;
        this.gson = new Gson();
        // Configure HTTP client with timeouts
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(30000)
                .setConnectionRequestTimeout(30000)
                .setSocketTimeout(30000)
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
        CloseableHttpResponse response = null;
        try {
            response = client.execute(request);
            if (validateResponse(response)) {
                HttpEntity entity = response.getEntity();
                String responseBody = EntityUtils.toString(entity);
                EntityUtils.consume(entity);
                return responseBody;
            } else {
                LOGGER.log(Level.WARNING, LOG_TAG + " " + operation + " failed for endpoint: " + exrEndpoint);
                return null;
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, LOG_TAG + " " + operation + " failed for endpoint: " + exrEndpoint, e);
            return null;
        } finally {
            request.reset();
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, LOG_TAG + " Failed to close HTTP response", e);
                }
            }
        }
    }

    /**
     * Process response JSON and handle wrapping for different response types.
     * @param responseBody The raw response body
     * @return Processed JsonObject with proper wrapping
     */
    private JsonObject processResponse(String responseBody) {
        JsonElement responseElement = gson.fromJson(responseBody, JsonElement.class);
        if (responseElement.isJsonObject()) {
            return responseElement.getAsJsonObject();
        } else {
            // Wrap non-objects (arrays, primitives) in a result field
            JsonObject wrapperObj = new JsonObject();
            wrapperObj.add("result", responseElement);
            return wrapperObj;
        }
    }

    /**
     * Execute a POST request to an EXR endpoint.
     * Transforms the method and params into EXR format.
     *
     * @param method The method name (e.g., "getblockhash")
     * @param params The parameters as a List of Objects
     * @return JsonObject response or null on error
     */
    public JsonObject execute(String method, List<Object> params) {
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
            return responseBody != null ? processResponse(responseBody) : null;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, LOG_TAG + " execute POST failed for " + method + " " + currency + " endpoint: " + endpoint, e);
            return null;
        } finally {
            httpPost.reset();
        }
    }

    /**
     * Execute a GET request to an EXR endpoint.
     *
     * @param method The method name (e.g., "fees", "heights")
     * @return JsonObject response or null on error
     */
    public JsonObject executeGet(String method) {
        String endpoint = exrEndpoint + "/xrs/" + method;
        HttpGet httpGet = new HttpGet(endpoint);
        httpGet.setHeader("Content-Type", "application/json");
        String responseBody = executeHttpRequest(httpGet, "execute GET for method " + method);
        return responseBody != null ? processResponse(responseBody) : null;
    }

    /**
     * Close the HTTP client resources.
     */
    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, LOG_TAG + " Failed to close HTTP client", e);
        }
    }

    private boolean validateResponse(HttpResponse response) {
        return response.getStatusLine().getStatusCode() == 200 &&
                response.getEntity() != null &&
                response.getEntity().getContentLength() != 0;
    }
}