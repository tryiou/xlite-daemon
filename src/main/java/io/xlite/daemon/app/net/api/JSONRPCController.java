package io.xlite.daemon.app.net.api;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.util.ConfigHelper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class JSONRPCController {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final ConcurrentHashMap<CoinInstance, JSONRPCServer> servers = new ConcurrentHashMap<>();
    // Per-coin serialization of every map access: retire→create happens
    // under the coin's lock, and plain lookups take it too, so no consumer
    // can observe a mid-retirement server. Per-coin (not global) scope:
    // a rebinding coin must not stall unrelated coins' event loops behind
    // its multi-second release-wait.
    private static final ConcurrentHashMap<CoinInstance, ReentrantLock> locks = new ConcurrentHashMap<>();
    private static JSONRPCMasterServer masterServer = new JSONRPCMasterServer(new ConfigHelper("master").getMasterRpcPort());

    public static JSONRPCMasterServer getMasterServer() {
        return masterServer;
    }

    public static JSONRPCServer getRPCServer(CoinInstance coinInstance) {
        if (coinInstance == null || coinInstance.getRPCPort() == -1) {
            throw new IllegalArgumentException("Bad coin instance");
        }
        ReentrantLock lock = lockFor(coinInstance);
        lock.lock();
        try {
            return getOrCreateLocked(coinInstance);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retires the coin's current server and returns a freshly created one
     * as a single step, serialized against every other controller access
     * for this coin. Callers must use this instead of an unsynchronized
     * remove+get pair: between retirement request and map release there
     * is a bounded wait, and only this lock guarantees no consumer of
     * this coin observes the retiring instance.
     */
    public static JSONRPCServer rebindRPCServer(CoinInstance coinInstance) {
        if (coinInstance == null || coinInstance.getRPCPort() == -1) {
            throw new IllegalArgumentException("Bad coin instance");
        }
        ReentrantLock lock = lockFor(coinInstance);
        lock.lock();
        try {
            removeLocked(coinInstance);
            return getOrCreateLocked(coinInstance);
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock lockFor(CoinInstance coinInstance) {
        return locks.computeIfAbsent(coinInstance, coin -> new ReentrantLock());
    }

    private static JSONRPCServer getOrCreateLocked(CoinInstance coinInstance) {
        return servers.computeIfAbsent(coinInstance,
                coin -> new JSONRPCServer(coin, coin.getRPCPort()));
    }

    private static void removeLocked(CoinInstance coinInstance) {
        JSONRPCServer server = servers.get(coinInstance);
        if (server == null)
            return;

        LOGGER.info("[rpc] rebind: retiring server " + server.lifecycleState());
        if (server.isAlive())
            server.deinit();

        // Wait (bounded) for the old listener to release its socket so an
        // immediate rebind on the same port cannot lose the race. Gate on
        // real kernel bindability, not Netty close-completion — the fd can
        // lag the closeFuture by ~1s.
        server.awaitPortRelease(3000);
        if (server.port() == coinInstance.getRPCPort()) {
            if (!server.awaitPortBindable(5000)) {
                LOGGER.warning("[rpc] rebind: port " + server.port()
                        + " still not bindable after release-wait; retrying at bind");
            }
        } else {
            // Config changed the port — the old port's bindability is
            // irrelevant to where we are about to bind.
            LOGGER.finer("[rpc] rebind: RPC port changed "
                    + server.port() + " -> " + coinInstance.getRPCPort()
                    + ", skipping old-port bindability wait");
        }
        LOGGER.info("[rpc] rebind: after release-wait "
                + server.lifecycleState());

        servers.remove(coinInstance);
    }
}
