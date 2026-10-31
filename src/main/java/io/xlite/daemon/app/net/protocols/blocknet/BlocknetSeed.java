package io.xlite.daemon.app.net.protocols.blocknet;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BlocknetSeed {
    private String address;
    private Integer port;

    private final AtomicInteger failCount = new AtomicInteger();
    private volatile long lastFailTime;

    private volatile boolean isActivePeer;

    BlocknetSeed(String address, int port) {
        this.address = address;
        this.port = port;
        failCount.set(0);
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
        failCount.incrementAndGet();
    }

    public void resetCounters() {
        this.lastFailTime = 0;
        failCount.set(0);
    }

    public void setActivePeer(boolean activePeer) {
        isActivePeer = activePeer;
    }

    public int getFailCount() {
        return failCount.get();
    }

    public long getLastFailTimeDiff() {
        return TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - lastFailTime);
    }

    public boolean isActivePeer() {
        return isActivePeer;
    }
}
