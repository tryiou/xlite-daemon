package io.xlite.daemon.app.net.api;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.server.HTTPServerInitializer;
import io.xlite.daemon.app.util.ConfigHelper;
import io.xlite.daemon.app.util.RpcPorts;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class JSONRPCServer extends Thread {
    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final CoinInstance coin;
    private final String bindAddress;

    /** Ports to try, in order; the first one that binds wins. */
    private final int[] candidates;

    private volatile int port;

    private volatile boolean stopping = false;
    private volatile boolean bound = false;
    private volatile boolean bindFailed = false;

    private Channel channel;

    JSONRPCServer(CoinInstance coin, int port, String bindAddress) {
        this(coin, port, bindAddress, 0);
    }

    JSONRPCServer(CoinInstance coin, int port, String bindAddress, int portWindow) {
        this(coin, port, bindAddress, RpcPorts.candidates(port, portWindow));
    }

    JSONRPCServer(CoinInstance coin, int port, String bindAddress, int[] candidates) {
        this.coin = coin;
        this.port = port;
        this.bindAddress = bindAddress;
        this.candidates = candidates.length == 0 ? new int[] {port} : candidates.clone();
        // Servant thread: never a JVM liveness anchor.
        setDaemon(true);
        setName("rpc-coin-" + CoinTickerUtils.tickerToString(coin.getTicker()));
    }

    public int port() {
        return port;
    }

    String bindAddress() {
        return bindAddress;
    }

    @Override
    public void run() {
        // Defense in depth: the CoinInstance start/reload gates refuse wildcards first, but
        // the bind site enforces it too — no caller path can put a wildcard listener on the
        // wire by omission.
        if (!ConfigHelper.isBindAddressValid(bindAddress)) {
            LOGGER.severe(
                    "[rpc-server] Refusing to bind for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ": rpcBindAddress '"
                            + bindAddress
                            + "' is not a specific"
                            + " interface. Set it to 127.0.0.1 or a named local address.");
            bindFailed = true;
            return;
        }
        // Resolve before allocating the event loop: a bad address refuses here without
        // leaking a worker group.
        InetAddress bindInetAddress;
        try {
            bindInetAddress = InetAddress.getByName(bindAddress);
        } catch (UnknownHostException e) {
            LOGGER.severe(
                    "[rpc-server] Unresolvable bind address '"
                            + bindAddress
                            + "' for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + " on port "
                            + port
                            + "; refusing to start");
            bindFailed = true;
            return;
        }
        try {
            // Shared group, daemon threads, released once at app shutdown.
            RpcBinder.Bound bound =
                    RpcBinder.bind(
                            serverBootstrap(NettyEventLoops.shared()),
                            bindInetAddress,
                            candidates,
                            () -> stopping);
            if (bound == null) {
                return;
            }
            channel = bound.channel();
            if (bound.port() != port) {
                LOGGER.warning(
                        "[rpc] "
                                + CoinTickerUtils.tickerToString(coin.getTicker())
                                + ": port "
                                + port
                                + " unavailable, bound "
                                + bound.port());
            }
            port = bound.port();

            // Emitted only after the bind actually succeeded.
            LOGGER.info(
                    "[rpc] RPC server listening for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + " on port "
                            + port
                            + " at "
                            + channel.localAddress()
                            + ".");
            this.bound = true;

            channel.closeFuture().sync();
        } catch (Exception e) {
            bindFailed = true;
            if (!stopping) {
                LOGGER.warning(
                        "[rpc-server] Error during RPC server operation for "
                                + CoinTickerUtils.tickerToString(coin.getTicker())
                                + e.getMessage());
            }
        }
    }

    /**
     * The bootstrap every coin listener shares: same options, its own child handler.
     *
     * <p>Built per attempt rather than kept: a listener that steps rebuilds it for each candidate
     * pass, and the options are the same object either way.
     */
    private ServerBootstrap serverBootstrap(EventLoopGroup workerGroup) {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap
                .group(workerGroup)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .channel(NioServerSocketChannel.class)
                .childHandler(new HTTPServerInitializer(coin));
        return bootstrap;
    }

    /**
     * Blocks until the listener has bound successfully, failed outright, or stop was requested —
     * whichever comes first.
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
                sleep(25);
            } catch (InterruptedException e) {
                interrupt();
                return bound;
            }
        }
        return bound;
    }

    /**
     * Best-effort wait until the previous listener has released its socket, so an immediate rebind
     * on the same port cannot race the async close.
     */
    public void awaitPortRelease(long timeoutMillis) {
        if (channel != null) {
            channel.closeFuture().awaitUninterruptibly(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Poll ACTUAL kernel bindability of the configured address and port. Netty's closeFuture can
     * complete while the old LISTEN socket is still being torn down (observed ~1s lag), so
     * close-completion alone is not a safe gate for an immediate rebind.
     *
     * @return true if the address became bindable in time
     */
    public boolean awaitPortBindable(long timeoutMillis) {
        return awaitPortBindable(bindAddress, port, timeoutMillis);
    }

    /** Static form usable without an instance (also unit-testable). */
    static boolean awaitPortBindable(String bindAddress, int port, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (portBindable(bindAddress, port)) {
                return true;
            }
            try {
                sleep(100);
            } catch (InterruptedException e) {
                // Static helper: restore the flag through the current thread.
                currentThread().interrupt();
                return false;
            }
        }
        return portBindable(bindAddress, port);
    }

    /**
     * Probe whether a listening socket could be opened on this address and port right now. Probes
     * the configured address, never the wildcard: a loopback-held port can still look
     * wildcard-bindable.
     */
    static boolean portBindable(String bindAddress, int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port), 1);
            return true;
        } catch (IOException | SecurityException e) {
            // probe failed (held, blocked or unresolvable)
            return false;
        }
    }

    /**
     * One-line lifecycle summary for diagnostics: thread state, bind flags and the recorded
     * channel's open/done/local-address state.
     */
    public String lifecycleState() {
        Channel ch = channel;
        return "srv="
                + System.identityHashCode(this)
                + " alive="
                + isAlive()
                + " stopping="
                + stopping
                + " bound="
                + bound
                + " bindFailed="
                + bindFailed
                + " channel="
                + (ch == null
                        ? "null"
                        : "open="
                                + ch.isOpen()
                                + " closeDone="
                                + ch.closeFuture().isDone()
                                + " local="
                                + ch.localAddress());
    }

    public void deinit() {
        stopping = true;
        LOGGER.finer("[json-rpc-server] Interrupting server.");

        if (channel != null) {
            channel.close().addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
        // Shared group: shutting it down here would retire every other coin.
    }
}
