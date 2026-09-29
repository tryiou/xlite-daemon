package io.xlite.daemon.app.net.api;

import io.xlite.daemon.app.net.api.http.master.HTTPServerInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import io.xlite.daemon.app.util.ConfigHelper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class JSONRPCMasterServer extends Thread {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final int port;
    private final String bindAddress;
    private boolean stopping = false;

    private Channel channel;
    private EventLoopGroup workerGroup;

    JSONRPCMasterServer(int port, String bindAddress) {
        setDaemon(true);
        setName("rpc-master");
        this.port = port;
        this.bindAddress = bindAddress;
    }

    public void run() {
        if (!ConfigHelper.isBindAddressValid(bindAddress)) {
            LOGGER.severe("[rpc-master] Refusing to bind master RPC server: rpcBindAddress '"
                    + bindAddress + "' is not a specific interface. Set it to 127.0.0.1"
                    + " or a named local address in config-master.json.");
            return;
        }
        InetSocketAddress bindSocketAddress;
        try {
            bindSocketAddress = new InetSocketAddress(
                    InetAddress.getByName(bindAddress), port);
        } catch (UnknownHostException e) {
            LOGGER.severe("[rpc-master] Unresolvable bind address '" + bindAddress
                    + "' for master RPC server on port " + port + "; refusing to start");
            return;
        }
        workerGroup = new NioEventLoopGroup(2,
                new DefaultThreadFactory("rpc-master", true));
        try {
            LOGGER.info("[rpc] Starting master RPC server on port " + port + ".");

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(workerGroup)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new HTTPServerInitializer());

            channel = bootstrap.bind(bindSocketAddress).sync().channel();

            // Emitted only after the bind actually succeeded — readiness
            // consumers (xlite-gui) anchor on this line, NOT on the
            // pre-bind "Starting" line below.
            LOGGER.info("[rpc-master] Master RPC server listening on port " + port + ".");

            channel.closeFuture().sync();
        } catch (Exception e) {
            if (!stopping) {
                LOGGER.warning("[rpc-master] Error during master RPC server operation" + e.getMessage());
            }
        }
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
