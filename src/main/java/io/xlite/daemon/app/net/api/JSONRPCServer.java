package io.xlite.daemon.app.net.api;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.api.http.server.HTTPServerInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class JSONRPCServer extends Thread {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final CoinInstance coin;
    private final int port;
    private volatile boolean stopping = false;
    private volatile boolean bound = false;
    private volatile boolean bindFailed = false;

    private Channel channel;
    private EventLoopGroup workerGroup;

    JSONRPCServer(CoinInstance coin, int port) {
        this.coin = coin;
        this.port = port;
        // Servant thread: must never block JVM termination on its own
        // liveness; lifecycle is governed by deinit()+bounded join.
        setDaemon(true);
        setName("rpc-coin-" + CoinTickerUtils.tickerToString(coin.getTicker()));
    }

    int port() {
        return port;
    }

    public void run() {
        // Daemon threads: the JVM must never be held hostage by an event
        // loop draining its graceful-shutdown quiet period after SIGINT
        // (DestroyJavaVM waited on these for the full grace window).
        workerGroup = new NioEventLoopGroup(5,
                new DefaultThreadFactory(
                        "rpc-coin-" + CoinTickerUtils.tickerToString(coin.getTicker()), true));
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(workerGroup)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new HTTPServerInitializer(coin));

            // Kernel release of the previous listener can lag Netty's
            // closeFuture by up to ~1s (observed: closeDone=true while the
            // old LISTEN entry persisted). Retry rather than fail hard.
            Channel boundChannel = null;
            Exception lastBindError = null;
            for (int attempt = 0; attempt < 20 && boundChannel == null && !stopping; attempt++) {
                ChannelFuture bindFuture = null;
                try {
                    bindFuture = bootstrap.bind(port);
                    bindFuture.sync();
                    boundChannel = bindFuture.channel();
                } catch (InterruptedException e) {
                    // Stop requested mid-retry — preserve the flag and bail
                    // out instead of laundering it into a bind failure. The
                    // bind may still complete on the event loop, so close
                    // whatever channel it produced.
                    Thread.currentThread().interrupt();
                    lastBindError = e;
                    if (bindFuture != null)
                        bindFuture.channel().close();
                    break;
                } catch (Exception e) {
                    lastBindError = e;
                    if (!isCauseBindException(e)) {
                        throw e;
                    }
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ie) {
                        // Thrown from inside this catch, so the sibling
                        // clause cannot intercept it — preserve the flag
                        // and bail out here instead of laundering it.
                        Thread.currentThread().interrupt();
                        lastBindError = ie;
                        break;
                    }
                }
            }
            if (boundChannel == null) {
                if (stopping) {
                    // Stop requested while retries were pending — a normal
                    // shutdown, not a bind failure. Do not stamp bindFailed.
                    LOGGER.finer("[rpc-server] bind retries abandoned: stopping");
                    return;
                }
                throw lastBindError != null ? lastBindError
                        : new IllegalStateException("bind failed without exception");
            }
            channel = boundChannel;

            // Emitted only after the bind actually succeeded.
            LOGGER.info("[rpc] RPC server listening for "
                    + CoinTickerUtils.tickerToString(coin.getTicker())
                    + " on port " + port + " at " + channel.localAddress() + ".");
            bound = true;

            channel.closeFuture().sync();
        } catch (Exception e) {
            bindFailed = true;
            if (!stopping) {
                LOGGER.warning("[rpc-server] Error during RPC server operation for " + CoinTickerUtils.tickerToString(coin.getTicker()) + e.getMessage());
            }
        }
    }

    private static boolean isCauseBindException(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof BindException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Blocks until the listener has bound successfully, failed outright,
     * or stop was requested — whichever comes first.
     * @return true iff the port is bound and accepting.
     */
    public boolean awaitBound(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (bound || bindFailed || stopping) return bound;
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

    /**
     * Poll ACTUAL kernel bindability of the port. Netty's closeFuture can
     * complete while the old LISTEN socket is still being torn down
     * (observed ~1s lag), so close-completion alone is not a safe gate for
     * an immediate rebind. Succeeds as soon as a probe listener can take
     * either wildcard family; gives up after the timeout.
     * @return true if the port became bindable in time
     */
    public boolean awaitPortBindable(long timeoutMillis) {
        return awaitPortBindable(port, timeoutMillis);
    }

    /**
     * Static form usable without an instance (also unit-testable).
     */
    static boolean awaitPortBindable(int port, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (portBindable(port)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return portBindable(port);
    }

    /**
     * Probe whether a listening socket could be opened on this port right now.
     */
    static boolean portBindable(int port) {
        for (String host : new String[]{"0.0.0.0", "::"}) {
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress(
                        InetAddress.getByName(host), port), 1);
                return true;
            } catch (Exception e) {
                // probe failed (held or blocked) — try the next family
            }
        }
        return false;
    }

    /**
     * One-line lifecycle summary for diagnostics: thread state, bind flags
     * and the recorded channel's open/done/local-address state.
     */
    public String lifecycleState() {
        Channel ch = channel;
        return "srv=" + System.identityHashCode(this)
                + " alive=" + isAlive()
                + " stopping=" + stopping
                + " bound=" + bound
                + " bindFailed=" + bindFailed
                + " channel=" + (ch == null ? "null"
                : "open=" + ch.isOpen()
                + " closeDone=" + ch.closeFuture().isDone()
                + " local=" + ch.localAddress());
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
