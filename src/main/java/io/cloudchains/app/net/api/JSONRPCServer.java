package io.cloudchains.app.net.api;

import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.api.http.server.HTTPServerInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class JSONRPCServer extends Thread {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final CoinInstance coin;
    private final int port;
    private boolean stopping = false;
    private volatile boolean bound = false;
    private volatile boolean bindFailed = false;

    private Channel channel;
    private EventLoopGroup workerGroup;

    JSONRPCServer(CoinInstance coin, int port) {
        this.coin = coin;
        this.port = port;
    }

    public void run() {
        workerGroup = new NioEventLoopGroup(5);
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(workerGroup)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new HTTPServerInitializer(coin));

            channel = bootstrap.bind(port).sync().channel();

            // Emitted only after the bind actually succeeded.
            LOGGER.info("[rpc] RPC server listening for " + CoinTickerUtils.tickerToString(coin.getTicker()) + " on port " + port + ".");
            bound = true;

            channel.closeFuture().sync();
        } catch (Exception e) {
            bindFailed = true;
            if (!stopping) {
                LOGGER.warning("[rpc-server] Error during RPC server operation for " + CoinTickerUtils.tickerToString(coin.getTicker()) + e.getMessage());
            }
        }
    }

    /**
     * Blocks until the listener has either bound successfully or failed.
     * @return true iff the port is bound and accepting.
     */
    public boolean awaitBound(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (bound || bindFailed) return bound;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return bound;
            }
        }
        return bound;
    }

    /**
     * Best-effort wait until the previous listener has released its socket,
     * so an immediate rebind on the same port cannot race the async close.
     */
    public void awaitPortRelease(long timeoutMillis) {
        if (channel != null)
            channel.closeFuture().awaitUninterruptibly(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void deinit() {
        stopping = true;
        LOGGER.finer("[json-rpc-server] Interrupting server.");

        if (channel != null) {
            channel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
