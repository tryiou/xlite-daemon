package io.cloudchains.app.net.protocols.blocknet;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks and calculates quality scores for discovered Blocknet peers.
 * Used by the peer discovery system to prioritize connections to reliable peers.
 */
public class PeerQualityScore {
    private final String peerAddress;
    private final AtomicLong lastSuccessfulConnection = new AtomicLong(0);
    private final AtomicLong lastFailedConnection = new AtomicLong(0);
    private final AtomicInteger successfulConnections = new AtomicInteger(0);
    private final AtomicInteger failedConnections = new AtomicInteger(0);
    private final AtomicLong totalLatency = new AtomicLong(0);
    private final AtomicInteger latencySamples = new AtomicInteger(0);
    private final AtomicLong lastMessageReceived = new AtomicLong(0);
    private final AtomicInteger messageCount = new AtomicInteger(0);
    
    // Configuration constants
    private static final long CONNECTION_AGE_DECAY_MS = 5 * 60 * 1000; // 5 minutes
    private static final long MESSAGE_AGE_DECAY_MS = 10 * 60 * 1000; // 10 minutes
    private static final int MAX_SCORE = 100;
    private static final int MIN_SCORE = 0;
    
    /**
     * Creates a new peer quality score tracker.
     * @param peerAddress The peer address this score represents
     */
    public PeerQualityScore(String peerAddress) {
        this.peerAddress = peerAddress;
    }
    
    /**
     * Records a successful connection to this peer.
     * @param latencyMs The connection latency in milliseconds
     */
    public void recordSuccessfulConnection(long latencyMs) {
        successfulConnections.incrementAndGet();
        lastSuccessfulConnection.set(System.currentTimeMillis());
        if (latencyMs > 0) {
            totalLatency.addAndGet(latencyMs);
            latencySamples.incrementAndGet();
        }
    }
    
    /**
     * Records a failed connection attempt to this peer.
     */
    public void recordFailedConnection() {
        failedConnections.incrementAndGet();
        lastFailedConnection.set(System.currentTimeMillis());
    }
    
    /**
     * Records receipt of a message from this peer.
     */
    public void recordMessageReceived() {
        messageCount.incrementAndGet();
        lastMessageReceived.set(System.currentTimeMillis());
    }
    
    /**
     * Calculates the current quality score for this peer (0-100).
     * @return Quality score where higher values indicate better peers
     */
    public int calculateScore() {
        int score = 0;
        
        // Connection reliability score (40% weight)
        score += calculateReliabilityScore() * 0.4;
        
        // Latency score (30% weight)
        score += calculateLatencyScore() * 0.3;
        
        // Activity score (20% weight)
        score += calculateActivityScore() * 0.2;
        
        // Recency bonus (10% weight)
        score += calculateRecencyBonus() * 0.1;
        
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }
    
    /**
     * Calculates reliability score based on success/failure ratio.
     */
    private int calculateReliabilityScore() {
        int totalAttempts = successfulConnections.get() + failedConnections.get();
        if (totalAttempts == 0) {
            return 50; // Neutral score for untested peers
        }
        
        double successRate = (double) successfulConnections.get() / totalAttempts;
        return (int) (successRate * 100);
    }
    
    /**
     * Calculates latency score based on average connection time.
     */
    private int calculateLatencyScore() {
        int samples = latencySamples.get();
        if (samples == 0) {
            return 50; // Neutral score for peers with no latency data
        }
        
        long avgLatency = totalLatency.get() / samples;
        
        // Score based on latency thresholds (lower is better)
        if (avgLatency < 100) {
            return 100; // Excellent
        } else if (avgLatency < 500) {
            return 80; // Good
        } else if (avgLatency < 1000) {
            return 60; // Average
        } else if (avgLatency < 2000) {
            return 40; // Poor
        } else {
            return 20; // Very poor
        }
    }
    
    /**
     * Calculates activity score based on message count and recent activity.
     */
    private int calculateActivityScore() {
        long now = System.currentTimeMillis();
        long lastActivity = lastMessageReceived.get();
        
        // Decay factor based on time since last message
        if (now - lastActivity > MESSAGE_AGE_DECAY_MS) {
            return 20; // Low activity due to staleness
        }
        
        int messages = messageCount.get();
        if (messages > 100) {
            return 100; // Very active
        } else if (messages > 50) {
            return 80; // Active
        } else if (messages > 20) {
            return 60; // Moderately active
        } else if (messages > 5) {
            return 40; // Low activity
        } else {
            return 20; // Minimal activity
        }
    }
    
    /**
     * Calculates recency bonus based on recent successful connections.
     */
    private int calculateRecencyBonus() {
        long now = System.currentTimeMillis();
        long lastSuccess = lastSuccessfulConnection.get();
        
        if (now - lastSuccess > CONNECTION_AGE_DECAY_MS) {
            return 0; // No bonus for stale connections
        }
        
        // Bonus for recent activity
        if (now - lastSuccess < 60000) { // Less than 1 minute
            return 100;
        } else if (now - lastSuccess < 300000) { // Less than 5 minutes
            return 80;
        } else if (now - lastSuccess < 600000) { // Less than 10 minutes
            return 60;
        } else {
            return 40;
        }
    }
    
    /**
     * Gets the average connection latency for this peer.
     * @return Average latency in milliseconds, or -1 if no data available
     */
    public long getAverageLatency() {
        int samples = latencySamples.get();
        if (samples == 0) {
            return -1;
        }
        return totalLatency.get() / samples;
    }
    
    /**
     * Gets the success rate for this peer.
     * @return Success rate as a percentage (0-100)
     */
    public double getSuccessRate() {
        int totalAttempts = successfulConnections.get() + failedConnections.get();
        if (totalAttempts == 0) {
            return 50.0; // Neutral for untested peers
        }
        return (double) successfulConnections.get() / totalAttempts * 100.0;
    }
    
    /**
     * Gets the total number of messages received from this peer.
     * @return Message count
     */
    public int getMessageCount() {
        return messageCount.get();
    }
    
    /**
     * Checks if this peer is considered reliable (score >= 70).
     * @return true if peer is reliable
     */
    public boolean isReliable() {
        return calculateScore() >= 70;
    }
    
    /**
     * Checks if this peer has recent activity (connected within decay period).
     * @return true if peer has recent activity
     */
    public boolean hasRecentActivity() {
        return System.currentTimeMillis() - lastSuccessfulConnection.get() < CONNECTION_AGE_DECAY_MS;
    }
    
    /**
     * Resets all statistics for this peer.
     * Useful for clearing stale data or testing scenarios.
     */
    public void reset() {
        lastSuccessfulConnection.set(0);
        lastFailedConnection.set(0);
        successfulConnections.set(0);
        failedConnections.set(0);
        totalLatency.set(0);
        latencySamples.set(0);
        lastMessageReceived.set(0);
        messageCount.set(0);
    }
    
    @Override
    public String toString() {
        return String.format("PeerQualityScore{address=%s, score=%d, successRate=%.1f%%, avgLatency=%dms, messages=%d}",
                peerAddress, calculateScore(), getSuccessRate(), getAverageLatency(), getMessageCount());
    }
}