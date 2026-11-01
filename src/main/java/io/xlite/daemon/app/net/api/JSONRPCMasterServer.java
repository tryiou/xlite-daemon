package io.xlite.daemon.app.net.api;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.xlite.daemon.app.net.api.http.master.HTTPServerInitializer;
import io.xlite.daemon.app.util.ConfigHelper;
import io.xlite.daemon.app.util.RpcPorts;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * The wallet-management endpoint.
 *
 * <p>It binds the first free port of its window and records that port in the master configuration
 * before it announces readiness. Two daemons, or a daemon restarted while a previous one still
 * holds the port, therefore coexist instead of the second one starting with no master endpoint at
 * all, and whoever reads the configuration next finds the port that actually answers.
 *
 * <p>The binding is deliberately the last startup step: nothing consumes the master endpoint until
 * the coins exist, so announcing it earlier would let a client ask for coins that are not up yet.
 */
public class JSONRPCMasterServer extends Thread {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final ConfigHelper config;
    private final String bindAddress;

    /** Ports to try, in order; the first one that binds wins. */
    private final int[] candidates;

    /** 0 until the listener has bound. */
    private volatile int port;

    private volatile boolean bound;
    private volatile boolean bindFailed;
    private volatile boolean stopping;

    private Channel channel;

    public JSONRPCMasterServer(ConfigHelper config) {
        this(config, RpcPorts.WINDOW);
    }

    JSONRPCMasterServer(ConfigHelper config, int window) {
        this.config = config;
        this.bindAddress = config.getRpcBindAddress();
        this.candidates = RpcPorts.candidates(config.getMasterRpcPort(), window);
        setDaemon(true);
        setName("rpc-master");
    }

    /**
     * The ports this listener may take, in order.
     *
     * <p>Copied on the way out: a caller that needs the window to avoid it (coin listeners do) must
     * not be able to move this listener's own candidates.
     */
    int[] candidatePorts() {
        return candidates.clone();
    }

    /** The bound port, or 0 while there is none. */
    public int port() {
        return port;
    }

    @Override
    public void run() {
        if (!ConfigHelper.isBindAddressValid(bindAddress)) {
            LOGGER.severe(
                    "[rpc-master] Refusing to bind master RPC server: rpcBindAddress '"
                            + bindAddress
                            + "' is not a specific interface. Set it to 127.0.0.1"
                            + " or a named local address in config-master.json.");
            bindFailed = true;
            return;
        }
        InetAddress bindInetAddress;
        try {
            bindInetAddress = InetAddress.getByName(bindAddress);
        } catch (UnknownHostException e) {
            LOGGER.severe(
                    "[rpc-master] Unresolvable bind address '"
                            + bindAddress
                            + "' for master RPC server on port "
                            + candidates[0]
                            + "; refusing to start");
            bindFailed = true;
            return;
        }

        try {
            LOGGER.info("[rpc] Starting master RPC server on port " + candidates[0] + ".");
            RpcBinder.Bound bound =
                    RpcBinder.bind(serverBootstrap(), bindInetAddress, candidates, () -> stopping);
            if (bound == null) {
                return;
            }
            channel = bound.channel();
            port = bound.port();
            // Recorded before the ready line: a client that reacts to that line reads the
            // configuration to find the endpoint, and must find the port that just bound.
            recordBoundPort();
            if (port != candidates[0]) {
                LOGGER.warning(
                        "[rpc] master: port " + candidates[0] + " unavailable, bound " + port);
            }
            // Emitted only after the bind actually succeeded, and after the port is recorded —
            // readiness consumers (xlite-gui) anchor on this line.
            LOGGER.info("[rpc-master] Master RPC server listening on port " + port + ".");
            this.bound = true;

            channel.closeFuture().sync();
        } catch (Exception e) {
            bindFailed = true;
            if (!stopping) {
                LOGGER.warning(
                        "[rpc-master] Error during master RPC server operation: " + describe(e));
            }
        }
    }

    /** Persists the port that bound, so the next start and the GUI agree on it. */
    private void recordBoundPort() {
        if (!config.setRpcPort(port)) {
            LOGGER.warning(
                    "[rpc-master] Bound port "
                            + port
                            + " could not be recorded in "
                            + config.getFilePath());
            return;
        }
        config.writeConfig();
    }

    /** The bootstrap: the same options as a coin listener, the master child handler. */
    private static ServerBootstrap serverBootstrap() {
        EventLoopGroup workerGroup = NettyEventLoops.shared();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap
                .group(workerGroup)
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .channel(NioServerSocketChannel.class)
                .childHandler(new HTTPServerInitializer());
        return bootstrap;
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return message != null ? message : error.getClass().getSimpleName();
    }

    /**
     * Blocks until the listener has bound, failed, or stop was requested — whichever comes first.
     *
     * @return true iff the port is bound and accepting.
     */
    public boolean awaitBound(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (bound || bindFailed || stopping) {
                return bound;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return bound;
    }

    public void deinit() {
        stopping = true;
        LOGGER.finer("[json-rpc-server] Interrupting server.");

        if (channel != null) {
            channel.close().addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
        // Shared group: its lifecycle belongs to application shutdown.
    }
}
