package io.cloudchains.app.net.api;

import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.util.ConfigHelper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class JSONRPCController {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final ConcurrentHashMap<CoinInstance, JSONRPCServer> servers = new ConcurrentHashMap<>();
    private static JSONRPCMasterServer masterServer = new JSONRPCMasterServer(new ConfigHelper("master").getMasterRpcPort());

    public static JSONRPCMasterServer getMasterServer() {
        return masterServer;
    }

    public static JSONRPCServer getRPCServer(CoinInstance coinInstance) {
        if (coinInstance == null || coinInstance.getRPCPort() == -1) {
            throw new IllegalArgumentException("Bad coin instance");
        }

        return servers.computeIfAbsent(coinInstance,
                coin -> new JSONRPCServer(coin, coin.getRPCPort()));
    }

    public static void removeRPCServer(CoinInstance coinInstance) {
        if (coinInstance == null || coinInstance.getRPCPort() == -1) {
            throw new IllegalArgumentException("Bad coin instance");
        }

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
