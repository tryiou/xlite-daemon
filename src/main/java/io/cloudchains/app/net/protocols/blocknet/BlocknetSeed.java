package io.cloudchains.app.net.protocols.blocknet;

import java.util.concurrent.TimeUnit;

public class BlocknetSeed {
    private String address;
    private Integer port;

    private int failCount;
    private long lastFailTime;

    private boolean isActivePeer;

    BlocknetSeed(String address, int port) {
        this.address = address;
        this.port = port;
        this.failCount = 0;
        this.lastFailTime = 0;
        this.isActivePeer = false;
    }

    public String getAddress() {
        return address;
    }

    public Integer getPort() {
        return port;
    }

    public void incrementFailCounter() {
        this.lastFailTime = System.currentTimeMillis();
        failCount++;
    }

    public void resetCounters() {
        this.lastFailTime = 0;
        this.failCount = 0;
    }

    public void setActivePeer(boolean activePeer) {
        isActivePeer = activePeer;
    }

    public int getFailCount() {
        return failCount;
    }

    public long getLastFailTimeDiff() {
        return TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - lastFailTime);
    }

    public boolean isActivePeer() {
        return isActivePeer;
    }
}
