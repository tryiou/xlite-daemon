package io.cloudchains.app.net.api.http.client;

import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.CoinTickerUtils;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class EXRServerPool {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    
    private final List<EXRServer> servers;
    private final AtomicInteger currentIndex;
    private final Map<CoinTicker, List<EXRServer>> coinToServersMap;
    private final Map<String, EXRServer> endpointToServerMap;
    private volatile boolean capabilitiesProbed;
    
    // Add synchronization lock for thread-safe map updates
    private final Object mapUpdateLock = new Object();
    
    public EXRServerPool(String endpoints) {
        this.servers = new CopyOnWriteArrayList<>();
        this.currentIndex = new AtomicInteger(0);
        this.coinToServersMap = new ConcurrentHashMap<>();
        this.endpointToServerMap = new ConcurrentHashMap<>();
        this.capabilitiesProbed = false;
        
        initializeServers(endpoints);
    }
    
    // Add constants for configuration
    private static final int CAPABILITY_PROBE_TIMEOUT_MS = 30000;
    
    public void startCapabilityProbing() {
        if (capabilitiesProbed || servers.isEmpty()) {
            return;
        }
        
        LOGGER.log(Level.INFO, "[exr-pool] Starting capability probing for " + servers.size() + " servers");
        
        // Start capability probing in background
        new Thread(this::probeAllCapabilities, "EXR-Capability-Prober").start();
    }
    
    public void probeAllCapabilities() {
        if (capabilitiesProbed) {
            return;
        }
        
        LOGGER.log(Level.INFO, "[exr-pool] Starting capability probing for " + servers.size() + " servers");
        
        // Probe each server concurrently
        List<Thread> probeThreads = new ArrayList<>();
        for (EXRServer server : servers) {
            Thread t = new Thread(() -> {
                try {
                    server.probeCapabilities();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "[exr-pool] Failed to probe server " + server.getEndpoint(), e);
                }
            });
            probeThreads.add(t);
            t.start();
        }
        
        // Wait for all probes to complete
        for (Thread t : probeThreads) {
            try {
                t.join(CAPABILITY_PROBE_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "[exr-pool] Capability probing interrupted", e);
            }
        }
        
        // Build coin-to-servers mapping with synchronization
        synchronized (mapUpdateLock) {
            coinToServersMap.clear();
            for (EXRServer server : servers) {
                for (io.cloudchains.app.net.CoinTicker coin : server.getSupportedCoins()) {
                    coinToServersMap.computeIfAbsent(coin, k -> new ArrayList<>()).add(server);
                }
            }
        }
        
        capabilitiesProbed = true;
        logCapabilityResults();
        LOGGER.log(Level.INFO, "[exr-pool] Capability probing completed");
    }
    
    private void logCapabilityResults() {
        LOGGER.log(Level.INFO, "[exr-pool] === EXR Server Capabilities ===");
        for (EXRServer server : servers) {
            if (server.isCapabilitiesProbed()) {
                String supportedCoins = server.getSupportedCoins().stream()
                    .map(coin -> io.cloudchains.app.net.CoinTickerUtils.tickerToString(coin))
                    .reduce((a, b) -> a + ", " + b).orElse("none");
                LOGGER.log(Level.INFO, "[exr-pool] " + server.getEndpoint() + " supports: " + supportedCoins);
            } else {
                LOGGER.log(Level.WARNING, "[exr-pool] " + server.getEndpoint() + " capability probe failed");
            }
        }
        
        LOGGER.log(Level.INFO, "[exr-pool] === Coin Distribution ===");
        for (io.cloudchains.app.net.CoinTicker coin : io.cloudchains.app.net.CoinTicker.coins()) {
            List<EXRServer> supportingServers = coinToServersMap.get(coin);
            if (supportingServers != null && !supportingServers.isEmpty()) {
                LOGGER.log(Level.INFO, "[exr-pool] " + io.cloudchains.app.net.CoinTickerUtils.tickerToString(coin) + " supported by " + supportingServers.size() + " servers");
            }
        }
    }
    
    private void initializeServers(String endpoints) {
        if (endpoints == null || endpoints.trim().isEmpty()) {
            return;
        }
        
        String[] endpointArray = endpoints.split(",");
        for (String endpoint : endpointArray) {
            String trimmed = endpoint.trim();
            if (!trimmed.isEmpty()) {
                EXRServer server = new EXRServer(trimmed);
                servers.add(server);
                endpointToServerMap.put(trimmed, server);
                LOGGER.log(Level.INFO, "[exr-pool] Added EXR server: " + trimmed);
            }
        }
        
        if (!servers.isEmpty()) {
            LOGGER.log(Level.INFO, "[exr-pool] Created pool with " + servers.size() + " servers");
        }
    }
    
    public EXRServer selectServer() {
        if (servers.isEmpty()) {
            LOGGER.log(Level.WARNING, "[exr-pool] No servers available for selection");
            return null;
        }
        
        // Try round-robin through healthy servers
        int start = currentIndex.getAndIncrement() % servers.size();
        for (int i = 0; i < servers.size(); i++) {
            int index = (start + i) % servers.size();
            EXRServer server = servers.get(index);
            if (server.isHealthy()) {
                LOGGER.log(Level.FINE, "[exr-pool] Selected server: " + server.getEndpoint());
                return server;
            }
        }
        LOGGER.log(Level.WARNING, "[exr-pool] No healthy servers available");
        return null; // All servers unhealthy
    }
    
    /**
     * Extract health filtering logic
     * @param supportingServers List of servers that support the coin
     * @return List of healthy servers that support the coin
     */
    private List<EXRServer> getHealthySupportingServers(List<EXRServer> supportingServers) {
        List<EXRServer> healthyServers = new ArrayList<>();
        for (EXRServer server : supportingServers) {
            if (server.isHealthy()) {
                healthyServers.add(server);
            }
        }
        return healthyServers;
    }

    /**
     * Extract server selection logic
     * @param healthyServers List of healthy servers
     * @param coin The coin to select a server for
     * @return Selected server or null if none available
     */
    private EXRServer selectFromHealthyServers(List<EXRServer> healthyServers, io.cloudchains.app.net.CoinTicker coin) {
        if (healthyServers.isEmpty()) {
            LOGGER.log(Level.SEVERE, "[exr-pool] NO HEALTHY EXR SERVERS FOR COIN: " +
                io.cloudchains.app.net.CoinTickerUtils.tickerToString(coin));
            return null;
        }
        
        int index = currentIndex.getAndIncrement() % healthyServers.size();
        EXRServer selectedServer = healthyServers.get(index);
        
        // Double-check that the selected server actually supports the coin
        if (!selectedServer.hasCapability(coin)) {
            LOGGER.log(Level.SEVERE, "[exr-pool] CRITICAL ERROR: Selected server " +
                selectedServer.getEndpoint() + " does NOT support coin " +
                io.cloudchains.app.net.CoinTickerUtils.tickerToString(coin));
            return null;
        }
        
        return selectedServer;
    }

    /**
     * Select a server for a specific coin with proper error handling
     * @param coin The coin to select a server for
     * @return Selected server or null if none available
     */
    public EXRServer selectServerForCoin(io.cloudchains.app.net.CoinTicker coin) {
        if (!capabilitiesProbed) {
            return null; // Wait for probing to complete
        }
        
        List<EXRServer> supportingServers = coinToServersMap.get(coin);
        if (supportingServers == null || supportingServers.isEmpty()) {
            LOGGER.log(Level.SEVERE, "[exr-pool] NO EXR SERVERS SUPPORT COIN: " +
                io.cloudchains.app.net.CoinTickerUtils.tickerToString(coin));
            return null; // FAIL - NO FALLBACK TO BASE_URL
        }
        
        List<EXRServer> healthyServers = getHealthySupportingServers(supportingServers);
        return selectFromHealthyServers(healthyServers, coin);
    }
    
    
    public Set<CoinTicker> getSupportedCoins() {
        Set<CoinTicker> supported = new HashSet<>();
        for (EXRServer server : servers) {
            supported.addAll(server.getSupportedCoins());
        }
        return supported;
    }
    
    public boolean hasServerForCoin(io.cloudchains.app.net.CoinTicker coin) {
        if (!capabilitiesProbed) {
            return !servers.isEmpty(); // Assume at least one server supports it
        }
        
        List<EXRServer> supportingServers = coinToServersMap.get(coin);
        return supportingServers != null && !supportingServers.isEmpty();
    }
    
    public void close() {
        for (EXRServer server : servers) {
            server.close();
        }
        servers.clear();
        coinToServersMap.clear();
        endpointToServerMap.clear();
    }
    
    public List<EXRServer> getServers() { 
        return new ArrayList<>(servers); 
    }
    
    public boolean isCapabilitiesProbed() { 
        return capabilitiesProbed; 
    }
    
    public int getServerCount() { 
        return servers.size(); 
    }
    
    public String getStatus() {
        int healthyCount = 0;
        for (EXRServer server : servers) {
            if (server.isHealthy()) {
                healthyCount++;
            }
        }
        return healthyCount + "/" + servers.size() + " servers healthy";
    }
}