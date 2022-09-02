package io.cloudchains.app.net.protocols.blocknet;

import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.ListenableFuture;
import org.bitcoinj.net.ClientConnectionManager;
import org.bitcoinj.net.StreamConnection;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

public class BlocknetBlockingClientManager extends AbstractIdleService implements ClientConnectionManager {
    private final SocketFactory socketFactory;
    private final Set<BlocknetBlockingClient> clients = Collections.synchronizedSet(new HashSet<>());

    private int connectTimeoutMillis = 1000;

    public BlocknetBlockingClientManager() {
        socketFactory = SocketFactory.getDefault();
    }

    public BlocknetBlockingClientManager(SocketFactory socketFactory) {
        this.socketFactory = checkNotNull(socketFactory);
    }

    @Override
    public ListenableFuture<SocketAddress> openConnection(SocketAddress serverAddress, StreamConnection connection) {
        try {
            if (!isRunning())
                throw new IllegalStateException();
            return new BlocknetBlockingClient(serverAddress, connection, connectTimeoutMillis, socketFactory, clients).getConnectFuture();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    @Override
    protected void startUp() throws Exception { }

    @Override
    protected void shutDown() throws Exception {
        synchronized (clients) {
            for (BlocknetBlockingClient client : clients)
                client.closeConnection();
        }
    }

    @Override
    public int getConnectedClientCount() {
        return clients.size();
    }

    @Override
    public void closeConnections(int n) {
        if (!isRunning())
            throw new IllegalStateException();
        synchronized (clients) {
            Iterator<BlocknetBlockingClient> it = clients.iterator();
            while (n-- > 0 && it.hasNext())
                it.next().closeConnection();
        }
    }
}
