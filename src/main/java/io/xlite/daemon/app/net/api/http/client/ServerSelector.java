package io.xlite.daemon.app.net.api.http.client;

import io.xlite.daemon.app.net.CoinTicker;

import java.util.List;

/**
 * Server selection strategy, injected into the pool so selection is separable from pool management.
 *
 * <p>Implementations can provide different selection algorithms such as: - Round-robin selection
 * (default implementation) - Weighted selection based on server performance - Random selection -
 * Health-based selection - Load-aware selection
 */
interface ServerSelector {

    /**
     * Select a healthy server from the provided list using the selection strategy. The selection
     * algorithm is implementation-specific and may use various criteria such as round-robin, random
     * selection, or performance-based selection.
     *
     * @param servers List of servers to select from (must not be null)
     * @return Selected healthy server or null if none available
     * @throws IllegalArgumentException if servers list is null
     */
    EXRServer selectHealthyServer(List<EXRServer> servers);

    /**
     * Select a server for a specific coin with proper error handling. This method ensures that the
     * selected server supports the requested coin and is in a healthy state.
     *
     * @param supportingServers List of servers that support the coin (can be null)
     * @param coin The coin to select a server for (must not be null)
     * @return Selected server or null if none available
     * @throws IllegalArgumentException if coin is null
     */
    EXRServer selectServerForCoin(List<EXRServer> supportingServers, CoinTicker coin);
}
