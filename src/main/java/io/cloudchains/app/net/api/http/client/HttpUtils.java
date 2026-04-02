package io.cloudchains.app.net.api.http.client;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Central HTTP operations utility class.
 * This class extracts common HTTP patterns from HTTPClient and EXRWrapper
 * to eliminate code duplication and provide a unified HTTP execution interface.
 */
public class HttpUtils {

    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);


    /**
     * Execute HTTP request with common boilerplate pattern.
     * This method provides unified HTTP execution with proper resource cleanup,
     * replacing the duplicate executeHttpRequest methods in HTTPClient and EXRWrapper.
     *
     * @param client The HTTP client to use for the request
     * @param request The HTTP request to execute (HttpGet or HttpPost)
     * @param operation Description of the operation for logging purposes
     * @return Response string or null on error
     */
    public static String executeHttpRequest(CloseableHttpClient client,
                                            HttpRequestBase request, String operation) {
        CloseableHttpResponse response = null;
        try {
            response = client.execute(request);
            if (validateResponse(response)) {
                HttpEntity entity = response.getEntity();
                String result = EntityUtils.toString(entity);
                EntityUtils.consume(entity);
                return result;
            } else {
                LOGGER.log(Level.WARNING, HttpClientConfig.LOG_TAG + " " + operation + " failed");
                return null;
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, HttpClientConfig.LOG_TAG + " " + operation + " failed", e);
            return null;
        } finally {
            request.reset();
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, HttpClientConfig.LOG_TAG + " Failed to close response", e);
                }
            }
        }
    }

    /**
     * Centralized response validation.
     * This method provides consistent response validation across all HTTP operations,
     * replacing the duplicate validateResponse methods in HTTPClient and EXRWrapper.
     *
     * @param response The HTTP response to validate
     * @return true if response is valid, false otherwise
     */
    public static boolean validateResponse(HttpResponse response) {
        return response.getStatusLine().getStatusCode() == 200 &&
                response.getEntity() != null &&
                response.getEntity().getContentLength() != 0;
    }

    /**
     * Private constructor to prevent instantiation.
     * This class only contains static utility methods and should not be instantiated.
     */
    private HttpUtils() {
        throw new UnsupportedOperationException("HttpUtils is a utility class and cannot be instantiated");
    }
}