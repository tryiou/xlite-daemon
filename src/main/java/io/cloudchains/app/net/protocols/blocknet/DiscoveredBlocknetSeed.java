package io.cloudchains.app.net.protocols.blocknet;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.net.InetSocketAddress;

/**
 * Extended BlocknetSeed that represents peers discovered through network discovery.
 * Tracks quality metrics and discovery metadata for dynamically found peers.
 */
public class DiscoveredBlocknetSeed extends BlocknetSeed {
    private final PeerQualityScore qualityScore;
    private final AtomicLong discoveredTime;
    private final AtomicLong lastSeenActive;
    private final AtomicBoolean isDiscovered;
    private final AtomicLong lastDiscoveryRequest;
    private static final long DISCOVERY_COOLDOWN = 5 * 60 * 1000; // 5 minutes
    
    /**
     * Creates a new discovered peer seed with quality tracking.
     * @param address Peer IP address
     * @param port Peer port number
     */
    public DiscoveredBlocknetSeed(String address, int port) {
        super(address, port);
        this.qualityScore = new PeerQualityScore(address + ":" + port);
        this.discoveredTime = new AtomicLong(System.currentTimeMillis());
        this.lastSeenActive = new AtomicLong(System.currentTimeMillis());
        this.isDiscovered = new AtomicBoolean(true);
        this.lastDiscoveryRequest = new AtomicLong(0);
    }
    
    /**
     * Creates a discovered peer from an existing seed (for manual peers that become discovered).
     * @param seed Existing BlocknetSeed to convert
     */
    public DiscoveredBlocknetSeed(BlocknetSeed seed) {
        this(seed.getAddress(), seed.getPort());
        // Copy existing state from the original seed
        if (seed.isActivePeer()) {
            setActivePeer(true);
        }
        // Reset fail counters since this peer was already validated
        resetCounters();
    }
    
    /**
     * Records a successful connection to this peer.
     * @param latencyMs Connection latency in milliseconds
     */
    public void recordSuccessfulConnection(long latencyMs) {
        qualityScore.recordSuccessfulConnection(latencyMs);
        lastSeenActive.set(System.currentTimeMillis());
    }
    
    /**
     * Records a failed connection attempt to this peer.
     */
    public void recordFailedConnection() {
        qualityScore.recordFailedConnection();
    }
    
    /**
     * Records receipt of a message from this peer.
     */
    public void recordMessageReceived() {
        qualityScore.recordMessageReceived();
        lastSeenActive.set(System.currentTimeMillis());
    }
    
    /**
     * Gets the quality score for this peer.
     * @return Peer quality score (0-100)
     */
    public int getQualityScore() {
        return qualityScore.calculateScore();
    }
    
    /**
     * Gets the peer quality tracker.
     * @return PeerQualityScore instance
     */
    public PeerQualityScore getQualityScoreTracker() {
        return qualityScore;
    }
    
    /**
     * Checks if this peer meets minimum quality requirements.
     * @param minimumScore Minimum score threshold
     * @return true if peer quality meets or exceeds threshold
     */
    public boolean meetsQualityThreshold(int minimumScore) {
        return getQualityScore() >= minimumScore;
    }
    
    /**
     * Checks if this peer has been active recently.
     * @param maxInactivityMinutes Maximum minutes of inactivity before considering peer stale
     * @return true if peer has been active within the specified time
     */
    public boolean hasRecentActivity(int maxInactivityMinutes) {
        long now = System.currentTimeMillis();
        long maxInactivityMs = maxInactivityMinutes * 60 * 1000;
        return (now - lastSeenActive.get()) < maxInactivityMs;
    }
    
    /**
     * Gets the time when this peer was discovered.
     * @return Discovery timestamp in milliseconds
     */
    public long getDiscoveredTime() {
        return discoveredTime.get();
    }
    
    /**
     * Checks if enough time has passed since the last discovery request.
     * @return true if discovery request can be made
     */
    public boolean canRequestDiscovery() {
        long now = System.currentTimeMillis();
        return (now - lastDiscoveryRequest.get()) > DISCOVERY_COOLDOWN;
    }
    
    /**
     * Marks that a discovery request was made to this peer.
     */
    public void markDiscoveryRequest() {
        lastDiscoveryRequest.set(System.currentTimeMillis());
    }
    
    /**
     * Notifies this peer about newly discovered peers from the network.
     * This can be used for peer-to-peer sharing of discovered peers.
     * @param discoveredPeers Set of newly discovered peer addresses
     */
    public void notifyNewPeers(Set<InetSocketAddress> discoveredPeers) {
        // Log discovered peers for potential future connections
        if (discoveredPeers != null && !discoveredPeers.isEmpty()) {
            // Could implement logic to validate and potentially add new peers
            // For now, just record that this peer provided discovery information
            recordMessageReceived();
        }
    }
    
    /**
     * Checks if this peer should be considered for connection based on quality and recency.
     * @param minimumScore Minimum quality score required
     * @param maxInactivityMinutes Maximum allowed inactivity in minutes
     * @return true if peer should be considered for connection
     */
    public boolean shouldConnect(int minimumScore, int maxInactivityMinutes) {
        return meetsQualityThreshold(minimumScore) && hasRecentActivity(maxInactivityMinutes);
    }
    
    /**
     * Gets how long this peer has been known to the system.
     * @return Time in milliseconds since discovery
     */
    public long getPeerAge() {
        return System.currentTimeMillis() - discoveredTime.get();
    }
    
    /**
     * Resets quality statistics for this peer.
     * Useful for testing or when peer behavior changes significantly.
     */
    public void resetQualityStats() {
        qualityScore.reset();
        discoveredTime.set(System.currentTimeMillis());
        lastSeenActive.set(System.currentTimeMillis());
    }
    
    /**
     * Checks if this peer is a discovered peer (vs manually configured).
     * @return true if this peer was discovered through network discovery
     */
    public boolean isDiscovered() {
        return isDiscovered.get();
    }
    
    /**
     * Gets a string representation with quality information.
     * @return Formatted string with peer details and quality score
     */
    @Override
    public String toString() {
        return String.format("DiscoveredBlocknetSeed{address=%s:%d, quality=%d, discovered=%dmin ago, active=%s}",
                getAddress(), getPort(), getQualityScore(),
                getPeerAge() / (60 * 1000),
                hasRecentActivity(30) ? "yes" : "no");
    }
}