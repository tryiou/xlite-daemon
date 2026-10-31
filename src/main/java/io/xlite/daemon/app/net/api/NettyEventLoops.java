package io.xlite.daemon.app.net.api;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;

import java.util.concurrent.TimeUnit;

/**
 * Owns the single Netty worker group shared by every HTTP listener in the daemon.
 *
 * <p>Each JSON-RPC listener used to allocate a private group of five threads, which cost 60 threads
 * for the twelve coins served today and would have cost 205 for the full coin set. A listener now
 * borrows this group, closes only its own channel on retirement, and never shuts the group down;
 * the group is released once, from application shutdown.
 *
 * <p>Sizing note: the channel handlers do synchronous upstream HTTP work on the event loop, so
 * these threads are request workers, not just selectors. The pool is therefore sized from the core
 * count (with a floor of eight for small hosts) instead of Netty's selector-oriented default, and
 * remains bounded regardless of how many coins are configured.
 *
 * <p>Threads are daemons: a wedged event loop can never hold the JVM open past its shutdown hook.
 * The group is recreated lazily after a shutdown, so tests and in-process restarts keep working.
 */
final class NettyEventLoops {

    /**
     * Request workers for every listener: two per core, never fewer than eight (a two-core host
     * still serves a multi-coin wallet), never scaled by coin count.
     */
    private static final int WORKER_THREADS =
            Math.max(8, Runtime.getRuntime().availableProcessors() * 2);

    private static EventLoopGroup shared;

    private NettyEventLoops() {}

    /**
     * Configured size of the shared pool. Exposed for tests and diagnostics; the pool never grows
     * past it regardless of how many listeners exist.
     */
    static int workerThreads() {
        return WORKER_THREADS;
    }

    /** Returns the process-wide worker group, creating it on first use (or after a shutdown). */
    static synchronized EventLoopGroup shared() {
        if (shared == null || shared.isShuttingDown()) {
            shared =
                    new MultiThreadIoEventLoopGroup(
                            WORKER_THREADS,
                            new DefaultThreadFactory("rpc-worker", true),
                            NioIoHandler.newFactory());
        }
        return shared;
    }

    /**
     * Releases the shared group. Call only after every listener has been retired; listeners must
     * never call this themselves.
     *
     * @return the shutdown future, so callers that want a bounded wait can await it; callers that
     *     do not can ignore it.
     */
    static synchronized Future<?> shutdown(long quietPeriodMillis, long timeoutMillis) {
        EventLoopGroup group = shared;
        shared = null;
        if (group == null) {
            return null;
        }
        return group.shutdownGracefully(quietPeriodMillis, timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
