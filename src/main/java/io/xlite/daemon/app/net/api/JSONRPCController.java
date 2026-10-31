package io.xlite.daemon.app.net.api;

import io.netty.util.concurrent.Future;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.util.ConfigHelper;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class JSONRPCController {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final ConcurrentHashMap<CoinInstance, JSONRPCServer> servers =
            new ConcurrentHashMap<>();
    // Per-coin serialization of every map access: retire→create happens
    // under the coin's lock, and plain lookups take it too, so no consumer
    // can observe a mid-retirement server. Per-coin (not global) scope:
    // a rebinding coin must not stall unrelated coins' event loops behind
    // its multi-second release-wait.
    private static final ConcurrentHashMap<CoinInstance, ReentrantLock> locks =
            new ConcurrentHashMap<>();
    private static final ConfigHelper masterConfig = new ConfigHelper(ConfigHelper.MASTER_TICKER);
    private static JSONRPCMasterServer masterServer =
            new JSONRPCMasterServer(
                    masterConfig.getMasterRpcPort(), masterConfig.getRpcBindAddress());

    public static JSONRPCMasterServer getMasterServer() {
        return masterServer;
    }

    /** The port the master listener is configured to bind. */
    public static int getMasterPort() {
        return masterConfig.getMasterRpcPort();
    }

    /**
     * Drops every handed-out server and its lock. Tests only: production keeps one server per coin
     * for the process lifetime.
     */
    static void resetForTest() {
        servers.clear();
        locks.clear();
    }

    /**
     * Releases the worker group shared by every listener. This is the only place allowed to stop
     * it: individual servers close their own channel and leave the group alone, so retiring one
     * coin cannot take another coin's RPC endpoint down.
     *
     * <p>Call after every coin instance and the master server have been deinitialized; waits at
     * most {@code timeoutMillis} for termination.
     */
    public static void shutdownEventLoops(long timeoutMillis) {
        Future<?> termination = NettyEventLoops.shutdown(0, timeoutMillis);
        if (termination != null) {
            termination.awaitUninterruptibly(timeoutMillis);
        }
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
     * Retires the coin's current server and returns a freshly created one as a single step,
     * serialized against every other controller access for this coin. Callers must use this instead
     * of an unsynchronized remove+get pair: between retirement request and map release there is a
     * bounded wait, and only this lock guarantees no consumer of this coin observes the retiring
     * instance.
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
        JSONRPCServer existing = servers.get(coinInstance);
        // A server thread cannot be restarted once it has terminated. A bind
        // failure or a previous deinit leaves such an instance in the map, so
        // replace it instead of handing a dead thread to start().
        if (existing != null
                && !existing.isAlive()
                && existing.getState() == Thread.State.TERMINATED) {
            servers.remove(coinInstance);
            existing = null;
        }
        if (existing != null) {
            return existing;
        }
        JSONRPCServer created =
                new JSONRPCServer(
                        coinInstance,
                        coinInstance.getRPCPort(),
                        coinInstance.getRpcBindAddress(),
                        coinInstance.getRpcCandidates());
        servers.put(coinInstance, created);
        return created;
    }

    private static void removeLocked(CoinInstance coinInstance) {
        JSONRPCServer server = servers.get(coinInstance);
        if (server == null) return;

        LOGGER.info("[rpc] rebind: retiring server " + server.lifecycleState());
        if (server.isAlive()) server.deinit();

        // Wait (bounded) for the old listener to release its socket so an
        // immediate rebind on the same port cannot lose the race. Gate on
        // real kernel bindability, not Netty close-completion — the fd can
        // lag the closeFuture by ~1s.
        server.awaitPortRelease(3000);
        if (server.port() == coinInstance.getRPCPort()
                && Objects.equals(coinInstance.getRpcBindAddress(), server.bindAddress())) {
            if (!server.awaitPortBindable(5000)) {
                LOGGER.warning(
                        "[rpc] rebind: port "
                                + server.port()
                                + " still not bindable after release-wait; retrying at bind");
            }
        } else {
            // Config changed the port or the bind address — the old socket's
            // bindability is irrelevant to where we are about to bind.
            LOGGER.finer(
                    "[rpc] rebind: RPC endpoint changed "
                            + server.bindAddress()
                            + ":"
                            + server.port()
                            + " -> "
                            + coinInstance.getRpcBindAddress()
                            + ":"
                            + coinInstance.getRPCPort()
                            + ", skipping old-endpoint bindability wait");
        }
        LOGGER.info("[rpc] rebind: after release-wait " + server.lifecycleState());

        servers.remove(coinInstance);
    }
}
