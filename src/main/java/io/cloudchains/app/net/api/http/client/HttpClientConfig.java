package io.cloudchains.app.net.api.http.client;

/**
 * Centralized configuration management for HTTP client settings.
 * This class consolidates all timeout and configuration constants
 * to eliminate duplication across HTTPClient, EXRWrapper, and other classes.
 */
public final class HttpClientConfig {

    // HTTP timeout configurations (in milliseconds)
    public static final int HTTP_TIMEOUT_MS = 30000;

    // Server management configurations
    public static final int HEALTH_CHECK_INTERVAL_MS = 5000;
    public static final int CAPABILITY_PROBE_TIMEOUT_MS = 30000;

    // Retry configurations
    public static final int MAX_RETRY_ATTEMPTS = 3;

    // Logging configuration
    public static final String LOG_TAG = "[httpclient]";

    // Capability probing configurations
    public static final int CAPABILITY_PROBE_WAIT_TIMEOUT_MS = 10000;
    public static final int CAPABILITY_PROBE_WAIT_INTERVAL_MS = 100;

    // Logging configurations
    public static final int LOG_COUNT_MODULO = 30;

    // Static initializer to validate configuration consistency
    static {
        // Validate that HTTP timeout is reasonable
        if (HTTP_TIMEOUT_MS < 1000) {
            throw new IllegalStateException("HTTP timeout too low: " + HTTP_TIMEOUT_MS + "ms");
        }

        // Validate that health check interval is reasonable
        if (HEALTH_CHECK_INTERVAL_MS < 100) {
            throw new IllegalStateException("Health check interval too low: " + HEALTH_CHECK_INTERVAL_MS + "ms");
        }

        // Validate that capability probe timeout is reasonable
        if (CAPABILITY_PROBE_TIMEOUT_MS < 1000) {
            throw new IllegalStateException("Capability probe timeout too low: " + CAPABILITY_PROBE_TIMEOUT_MS + "ms");
        }

        // Validate that max retry attempts is reasonable
        if (MAX_RETRY_ATTEMPTS < 0) {
            throw new IllegalStateException("Max retry attempts cannot be negative: " + MAX_RETRY_ATTEMPTS);
        }
    }

    /**
     * Private constructor to prevent instantiation.
     * This class only contains static constants and should not be instantiated.
     */
    private HttpClientConfig() {
        throw new UnsupportedOperationException("HttpClientConfig is a utility class and cannot be instantiated");
    }
}