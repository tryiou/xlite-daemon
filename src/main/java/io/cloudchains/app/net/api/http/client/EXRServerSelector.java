package io.cloudchains.app.net.api.http.client;

import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.CoinTickerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Server selection interface defining the contract for server selection strategies.
 * This interface enables dependency injection and loose coupling between
 * server pool management and server selection logic.
 *
 * Implementations can provide different selection algorithms such as:
 * - Round-robin selection (default implementation)
 * - Weighted selection based on server performance
 * - Random selection
 * - Health-based selection
 * - Load-aware selection
 */
interface ServerSelector {

    /**
     * Select a healthy server from the provided list using the selection strategy.
     * The selection algorithm is implementation-specific and may use various criteria
     * such as round-robin, random selection, or performance-based selection.
     *
     * @param servers List of servers to select from (must not be null)
     * @return Selected healthy server or null if none available
     * @throws IllegalArgumentException if servers list is null
     */
    EXRServer selectHealthyServer(List<EXRServer> servers);

    /**
     * Select a server for a specific coin with proper error handling.
     * This method ensures that the selected server supports the requested coin
     * and is in a healthy state.
     *
     * @param supportingServers List of servers that support the coin (can be null)
     * @param coin The coin to select a server for (must not be null)
     * @return Selected server or null if none available
     * @throws IllegalArgumentException if coin is null
     */
    EXRServer selectServerForCoin(List<EXRServer> supportingServers, CoinTicker coin);
}

/**
 * Default implementation of ServerSelector using round-robin selection strategy.
 * This class provides the original server selection logic extracted from
 * EXRServerPool to separate server selection concerns from pool management.
 */
class EXRServerSelectorImpl implements ServerSelector {

    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final AtomicInteger currentIndex;

    /**
     * Constructor for EXRServerSelectorImpl.
     * @param currentIndex Shared atomic integer for round-robin selection
     */
    public EXRServerSelectorImpl(AtomicInteger currentIndex) {
        this.currentIndex = currentIndex;
    }

    /**
     * Select a healthy server from the provided list using round-robin.
     * This method implements the same logic as EXRServerPool.selectServer()
     * but extracts it into a separate utility class.
     *
     * @param servers List of servers to select from (must not be null)
     * @return Selected healthy server or null if none available
     * @throws IllegalArgumentException if servers list is null
     */
    @Override
    public EXRServer selectHealthyServer(List<EXRServer> servers) {
        if (servers.isEmpty()) {
            LOGGER.log(Level.WARNING, "[server-selector] No servers available for selection");
            return null;
        }

        // Try round-robin through healthy servers
        int start = currentIndex.getAndIncrement() % servers.size();
        for (int i = 0; i < servers.size(); i++) {
            int index = (start + i) % servers.size();
            EXRServer server = servers.get(index);
            if (server.isHealthy()) {
                LOGGER.log(Level.FINE, "[server-selector] Selected server: " + server.getEndpoint());
                return server;
            }
        }

        LOGGER.log(Level.WARNING, "[server-selector] No healthy servers available");
        return null; // All servers unhealthy
    }

    /**
     * Select a server for a specific coin with proper error handling.
     * This method implements coin-aware server selection logic,
     * extracting it from EXRServerPool.selectServerForCoin().
     *
     * @param supportingServers List of servers that support the coin (can be null)
     * @param coin The coin to select a server for (must not be null)
     * @return Selected server or null if none available
     * @throws IllegalArgumentException if coin is null
     */
    @Override
    public EXRServer selectServerForCoin(List<EXRServer> supportingServers, CoinTicker coin) {
        if (supportingServers == null || supportingServers.isEmpty()) {
            LOGGER.log(Level.SEVERE, "[server-selector] NO EXR SERVERS SUPPORT COIN: " +
                    CoinTickerUtils.tickerToString(coin));
            return null; // FAIL - NO FALLBACK TO BASE_URL
        }

        // Filter healthy servers
        List<EXRServer> healthyServers = getHealthySupportingServers(supportingServers);

        // Select from healthy servers
        return selectFromHealthyServers(healthyServers, coin);
    }

    /**
     * Extract health filtering logic.
     * This method filters the list to only include healthy servers.
     *
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
     * Extract server selection logic.
     * This method selects a server from the healthy servers list
     * using round-robin selection.
     *
     * @param healthyServers List of healthy servers
     * @param coin The coin to select a server for
     * @return Selected server or null if none available
     */
    private EXRServer selectFromHealthyServers(List<EXRServer> healthyServers, CoinTicker coin) {
        if (healthyServers.isEmpty()) {
            LOGGER.log(Level.SEVERE, "[server-selector] NO HEALTHY EXR SERVERS FOR COIN: " +
                    CoinTickerUtils.tickerToString(coin));
            return null;
        }

        int index = currentIndex.getAndIncrement() % healthyServers.size();
        EXRServer selectedServer = healthyServers.get(index);

        // Double-check that the selected server actually supports the coin
        if (!selectedServer.hasCapability(coin)) {
            LOGGER.log(Level.SEVERE, "[server-selector] CRITICAL ERROR: Selected server " +
                    selectedServer.getEndpoint() + " does NOT support coin " +
                    CoinTickerUtils.tickerToString(coin));
            return null;
        }

        return selectedServer;
    }

}