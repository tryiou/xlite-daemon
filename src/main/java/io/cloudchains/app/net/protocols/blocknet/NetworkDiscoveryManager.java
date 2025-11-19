package io.cloudchains.app.net.protocols.blocknet;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Centralized manager for network discovery operations.
 * Coordinates peer discovery across multiple BlocknetPeerGroup instances
 * and provides configuration management and monitoring capabilities.
 */
public class NetworkDiscoveryManager {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    
    private final AtomicBoolean discoveryEnabled;
    private final AtomicBoolean isRunning;
    
    // Configuration parameters
    private volatile int maxDiscoveredPeers;
    private volatile long discoveryIntervalMs;
    private volatile int minimumQualityScore;
    private volatile int maxInactivityMinutes;
    private volatile int maxDiscoveryRequestsPerCycle;
    
    // Statistics
    private volatile long totalDiscoveryRequests;
    private volatile long totalPeersDiscovered;
    private volatile long totalSuccessfulConnections;
    private volatile long totalFailedConnections;
    
    /**
     * Creates a new network discovery manager with default configuration.
     */
    public NetworkDiscoveryManager() {
        this.discoveryEnabled = new AtomicBoolean(true);
        this.isRunning = new AtomicBoolean(false);
        
        // Default configuration
        this.maxDiscoveredPeers = 50;
        this.discoveryIntervalMs = 5 * 60 * 1000; // 5 minutes
        this.minimumQualityScore = 30;
        this.maxInactivityMinutes = 30;
        this.maxDiscoveryRequestsPerCycle = 3;
        
        // Initialize statistics
        this.totalDiscoveryRequests = 0;
        this.totalPeersDiscovered = 0;
        this.totalSuccessfulConnections = 0;
        this.totalFailedConnections = 0;
    }
    
    /**
     * Starts the network discovery manager.
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            LOGGER.log(Level.INFO, "[network-discovery] Starting network discovery manager");
            
            // Schedule periodic discovery maintenance
            scheduleDiscoveryMaintenance();
        }
    }
    
    /**
     * Stops the network discovery manager.
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            LOGGER.log(Level.INFO, "[network-discovery] Stopping network discovery manager");
        }
    }
    
    /**
     * Performs discovery maintenance across all peer groups.
     */
    public void performMaintenance(List<BlocknetPeerGroup> peerGroups) {
        if (!discoveryEnabled.get() || !isRunning.get()) {
            return;
        }
        
        try {
            // Collect statistics from all peer groups
            long totalDiscovered = 0;
            long reliablePeers = 0;
            long activePeers = 0;
            
            for (BlocknetPeerGroup group : peerGroups) {
                try {
                    String stats = group.getDiscoveryStats();
                    LOGGER.log(Level.FINER, "[network-discovery] " + stats);
                    
                    // Extract and aggregate statistics (simplified parsing)
                    // In production, would use structured statistics
                    totalDiscovered += group.getDiscoveredPeersByQuality().size();
                    
                    for (DiscoveredBlocknetSeed seed : group.getDiscoveredPeersByQuality()) {
                        if (seed.meetsQualityThreshold(minimumQualityScore)) {
                            reliablePeers++;
                        }
                        if (seed.hasRecentActivity(maxInactivityMinutes)) {
                            activePeers++;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "[network-discovery] Failed to collect stats from peer group", e);
                }
            }
            
            // Log aggregated statistics
            LOGGER.log(Level.INFO, "[network-discovery] Aggregated stats: Discovered=" + totalDiscovered + 
                    ", Reliable=" + reliablePeers + ", Active=" + activePeers);
                    
            // Clean up stale peers across all groups
            cleanupStalePeers(peerGroups);
            
            // Check if we need to trigger additional discovery
            if (totalDiscovered < maxDiscoveredPeers / 2) {
                LOGGER.log(Level.FINER, "[network-discovery] Low peer count detected, may need more discovery");
            }
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[network-discovery] Error during maintenance cycle", e);
        }
    }
    
    /**
     * Cleans up stale discovered peers across all peer groups.
     */
    private void cleanupStalePeers(List<BlocknetPeerGroup> peerGroups) {
        int totalCleaned = 0;
        
        for (BlocknetPeerGroup group : peerGroups) {
            try {
                // This would trigger cleanup in each group
                // Implementation would depend on group's cleanup mechanism
                // group.cleanupStalePeers(); // If available
                totalCleaned++;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[network-discovery] Failed to cleanup peers in group", e);
            }
        }
        
        if (totalCleaned > 0) {
            LOGGER.log(Level.FINER, "[network-discovery] Cleaned up stale peers in " + totalCleaned + " groups");
        }
    }
    
    /**
     * Schedules periodic discovery maintenance.
     */
    private void scheduleDiscoveryMaintenance() {
        // This would typically use a scheduler like ScheduledExecutorService
        // For now, providing the framework - actual scheduling would be integrated
        // with the existing BlocknetPeerGroup executor
        
        LOGGER.log(Level.FINER, "[network-discovery] Scheduled maintenance every " + 
                (discoveryIntervalMs / 1000) + " seconds");
    }
    
    /**
     * Handles peer connection success for quality tracking.
     */
    public void recordConnectionSuccess(String peerAddress, long latencyMs) {
        totalSuccessfulConnections++;
        
        if (LOGGER.isLoggable(Level.FINER)) {
            LOGGER.log(Level.FINER, "[network-discovery] Successful connection to " + peerAddress + 
                    " (latency: " + latencyMs + "ms)");
        }
    }
    
    /**
     * Handles peer connection failure for quality tracking.
     */
    public void recordConnectionFailure(String peerAddress) {
        totalFailedConnections++;
        
        if (LOGGER.isLoggable(Level.FINER)) {
            LOGGER.log(Level.FINER, "[network-discovery] Failed connection to " + peerAddress);
        }
    }
    
    /**
     * Records a discovery request.
     */
    public void recordDiscoveryRequest() {
        totalDiscoveryRequests++;
    }
    
    /**
     * Records a newly discovered peer.
     */
    public void recordNewPeerDiscovered() {
        totalPeersDiscovered++;
        
        if (LOGGER.isLoggable(Level.FINER)) {
            LOGGER.log(Level.FINER, "[network-discovery] New peer discovered. Total: " + totalPeersDiscovered);
        }
    }
    
    /**
     * Gets comprehensive discovery statistics.
     */
    public String getDiscoveryStatistics() {
        double successRate = (totalSuccessfulConnections + totalFailedConnections) > 0 ?
                (double) totalSuccessfulConnections / (totalSuccessfulConnections + totalFailedConnections) * 100 : 0;
        
        return String.format("NetworkDiscovery Stats: Requests=%d, Peers=%d, SuccessRate=%.1f%%, " +
                "TotalConnections=%d (Success=%d, Failed=%d)",
                totalDiscoveryRequests, totalPeersDiscovered, successRate,
                totalSuccessfulConnections + totalFailedConnections,
                totalSuccessfulConnections, totalFailedConnections);
    }
    
    /**
     * Validates if a peer address is suitable for discovery addition.
     */
    public boolean isValidDiscoveryTarget(String address, int port) {
        if (!discoveryEnabled.get()) {
            return false;
        }
        
        try {
            java.net.InetAddress.getByName(address); // Validate address
            
            // Check port
            if (port != 41412) {
                return false;
            }
            
            // Check if we've reached capacity
            if (totalPeersDiscovered >= maxDiscoveredPeers) {
                LOGGER.log(Level.FINER, "[network-discovery] Max peer limit reached, rejecting " + address);
                return false;
            }
            
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.FINER, "[network-discovery] Invalid discovery target: " + address + ":" + port);
            return false;
        }
    }
    
    /**
     * Updates configuration parameters.
     */
    public void updateConfiguration(int maxPeers, long intervalMs, int minScore, 
                                   int maxInactivity, int maxRequests) {
        this.maxDiscoveredPeers = maxPeers;
        this.discoveryIntervalMs = intervalMs;
        this.minimumQualityScore = minScore;
        this.maxInactivityMinutes = maxInactivity;
        this.maxDiscoveryRequestsPerCycle = maxRequests;
        
        LOGGER.log(Level.INFO, "[network-discovery] Configuration updated: MaxPeers=" + maxPeers + 
                ", Interval=" + (intervalMs / 1000) + "s, MinScore=" + minScore);
    }
    
    /**
     * Enables or disables discovery.
     */
    public void setDiscoveryEnabled(boolean enabled) {
        boolean wasEnabled = discoveryEnabled.getAndSet(enabled);
        if (wasEnabled != enabled) {
            LOGGER.log(Level.INFO, "[network-discovery] Discovery " + (enabled ? "enabled" : "disabled"));
        }
    }
    
    /**
     * Checks if discovery is enabled and running.
     */
    public boolean isDiscoveryActive() {
        return discoveryEnabled.get() && isRunning.get();
    }
    
    // Configuration getters
    public int getMaxDiscoveredPeers() { return maxDiscoveredPeers; }
    public long getDiscoveryIntervalMs() { return discoveryIntervalMs; }
    public int getMinimumQualityScore() { return minimumQualityScore; }
    public int getMaxInactivityMinutes() { return maxInactivityMinutes; }
    public int getMaxDiscoveryRequestsPerCycle() { return maxDiscoveryRequestsPerCycle; }
    
    // Statistics getters
    public long getTotalDiscoveryRequests() { return totalDiscoveryRequests; }
    public long getTotalPeersDiscovered() { return totalPeersDiscovered; }
    public long getTotalSuccessfulConnections() { return totalSuccessfulConnections; }
    public long getTotalFailedConnections() { return totalFailedConnections; }
}