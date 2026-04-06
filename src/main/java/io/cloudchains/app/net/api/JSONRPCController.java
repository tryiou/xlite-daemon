package io.cloudchains.app.net.api;

import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.util.ConfigHelper;

import java.util.concurrent.ConcurrentHashMap;

public class JSONRPCController {

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

        if (server.isAlive())
            server.deinit();

        servers.remove(coinInstance);
    }
}
