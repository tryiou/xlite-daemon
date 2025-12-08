package io.cloudchains.app.net.api.http.client;

import io.cloudchains.app.net.CoinTicker;
import org.apache.http.client.config.RequestConfig;
import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class EXRServer {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    
    private final String endpoint;
    private final EXRWrapper wrapper;
    private volatile boolean healthy;
    private long lastHealthCheck;
    private final java.util.Set<io.cloudchains.app.net.CoinTicker> supportedCoins;
    private volatile boolean capabilitiesProbed;
    
    // Add constants for configuration
    private static final int HEALTH_CHECK_INTERVAL_MS = 5000;
    private static final int CAPABILITY_PROBE_TIMEOUT_MS = 30000;
    
    public EXRServer(String endpoint) {
        // Store endpoint with trailing slash for consistency
        if (endpoint.endsWith("/")) {
            this.endpoint = endpoint;
        } else {
            this.endpoint = endpoint + "/";
        }
        
        // EXRWrapper expects endpoint without trailing slash
        String wrapperEndpoint = this.endpoint.endsWith("/") ?
            this.endpoint.substring(0, this.endpoint.length() - 1) : this.endpoint;
        
        this.wrapper = new EXRWrapper(wrapperEndpoint);
        this.healthy = true;
        this.lastHealthCheck = 0;
        this.supportedCoins = new java.util.HashSet<>();
        this.capabilitiesProbed = false;
    }
    
    public boolean probeCapabilities() {
        if (capabilitiesProbed) {
            return true;
        }
        
        if (!isHealthy()) {
            return false;
        }
        
        try {
            // CALL HEIGHTS ONCE - not per coin
            com.google.gson.JsonObject result = wrapper.executeGet("heights");
            if (result != null && result.has("result")) {
                com.google.gson.JsonObject heights = result.getAsJsonObject("result");
                
                // Extract ALL supported coins from single response
                // Only include coins that have non-null values (null means not supported)
                for (String coinName : heights.keySet()) {
                    com.google.gson.JsonElement heightValue = heights.get(coinName);
                    
                    if (heightValue.isJsonNull()) {
                        continue; // Skip unsupported coins (null values)
                    }
                    
                    try {
                        io.cloudchains.app.net.CoinTicker coin = io.cloudchains.app.net.CoinTickerUtils.stringToTicker(coinName);
                        if (coin != null) {
                            supportedCoins.add(coin);
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.FINER, "[exr-server] Failed to map coin " + coinName, e);
                    }
                }
            }
            
            capabilitiesProbed = true;
            LOGGER.log(Level.INFO, "[exr-server] Probed capabilities for " + endpoint + ", supports: " + supportedCoins.size() + " coins: " +
                supportedCoins.stream().map(io.cloudchains.app.net.CoinTickerUtils::tickerToString)
                    .reduce((a, b) -> a + ", " + b).orElse("none"));
            return !supportedCoins.isEmpty();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[exr-server] Failed to probe capabilities for " + endpoint, e);
            return false;
        }
    }
    
    public boolean isHealthy() {
        long now = System.currentTimeMillis();
        if (now - lastHealthCheck < HEALTH_CHECK_INTERVAL_MS) {
            return healthy;
        }
        
        try {
            com.google.gson.JsonObject result = wrapper.executeGet("heights");
            healthy = result != null && !result.isJsonNull();
        } catch (Exception e) {
            healthy = false;
            LOGGER.log(Level.WARNING, "[exr-server] Health check failed for " + endpoint, e);
        }
        lastHealthCheck = now;
        return healthy;
    }
    
    public com.google.gson.JsonObject execute(String method, List<Object> params) {
        if (!isHealthy()) {
            return null;
        }
        
        // Server selection should have already filtered by coin support
        // Remove redundant capability check to avoid race conditions
        return wrapper.execute(method, params);
    }
    
    public com.google.gson.JsonObject executeGet(String method) {
        return isHealthy() ? wrapper.executeGet(method) : null;
    }
    
    public void close() {
        wrapper.close();
    }
    
    public String getEndpoint() { 
        return endpoint; 
    }
    
    public boolean isCapabilitiesProbed() {
        return capabilitiesProbed;
    }
    
    public java.util.Set<io.cloudchains.app.net.CoinTicker> getSupportedCoins() {
        return new java.util.HashSet<>(supportedCoins);
    }
    
    public boolean hasCapability(io.cloudchains.app.net.CoinTicker coin) {
        return supportedCoins.contains(coin);
    }
}