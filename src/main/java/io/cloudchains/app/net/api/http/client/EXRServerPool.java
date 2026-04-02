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
    // Server selector interface for dependency injection
    private final ServerSelector serverSelector;

    /**
     * Constructor with dependency injection for flexible server selection.
     * @param endpoints Comma-separated list of server endpoints
     * @param serverSelector Server selection strategy implementation
     */
    public EXRServerPool(String endpoints, ServerSelector serverSelector) {
        this.servers = new CopyOnWriteArrayList<>();
        this.currentIndex = new AtomicInteger(0);
        this.coinToServersMap = new ConcurrentHashMap<>();
        this.endpointToServerMap = new ConcurrentHashMap<>();
        this.capabilitiesProbed = false;
        // Initialize server selector with dependency injection
        this.serverSelector = serverSelector;
        initializeServers(endpoints);
    }

    /**
     * Backward compatibility constructor using default round-robin selection.
     * @param endpoints Comma-separated list of server endpoints
     */
    public EXRServerPool(String endpoints) {
        this(endpoints, new EXRServerSelectorImpl(new AtomicInteger(0)));
    }
    // Use centralized configuration constants
    private static final int CAPABILITY_PROBE_TIMEOUT_MS = HttpClientConfig.CAPABILITY_PROBE_TIMEOUT_MS;

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
                for (CoinTicker coin : server.getSupportedCoins()) {
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
                        .map(coin -> CoinTickerUtils.tickerToString(coin))
                        .reduce((a, b) -> a + ", " + b).orElse("none");
                LOGGER.log(Level.INFO, "[exr-pool] " + server.getEndpoint() + " supports: " + supportedCoins);
            } else {
                LOGGER.log(Level.WARNING, "[exr-pool] " + server.getEndpoint() + " capability probe failed");
            }
        }
        LOGGER.log(Level.INFO, "[exr-pool] === Coin Distribution ===");
        for (CoinTicker coin : CoinTicker.coins()) {
            List<EXRServer> supportingServers = coinToServersMap.get(coin);
            if (supportingServers != null && !supportingServers.isEmpty()) {
                LOGGER.log(Level.INFO, "[exr-pool] " + CoinTickerUtils.tickerToString(coin) + " supported by " + supportingServers.size() + " servers");
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
        return serverSelector.selectHealthyServer(servers);
    }

    /**
     * Select a server for a specific coin with proper error handling
     * @param coin The coin to select a server for
     * @return Selected server or null if none available
     * @throws IllegalArgumentException if coin is null
     */
    public EXRServer selectServerForCoin(CoinTicker coin) {
        if (coin == null) {
            throw new IllegalArgumentException("Coin cannot be null");
        }
        if (!capabilitiesProbed) {
            return null; // Wait for probing to complete
        }
        List<EXRServer> supportingServers = coinToServersMap.get(coin);
        return serverSelector.selectServerForCoin(supportingServers, coin);
    }

    public Set<CoinTicker> getSupportedCoins() {
        Set<CoinTicker> supported = new HashSet<>();
        for (EXRServer server : servers) {
            supported.addAll(server.getSupportedCoins());
        }
        return supported;
    }

    public boolean hasServerForCoin(CoinTicker coin) {
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