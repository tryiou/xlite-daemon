package io.xlite.daemon.app.net.api;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.BooleanSupplier;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Binds a listener to the first candidate port that accepts it.
 *
 * <p>Both the master endpoint and every coin endpoint step upward when their preferred port is
 * already held, and neither probes: binding is the availability test.
 *
 * <p>The retry budget depends on how far the listener can move. A single candidate has nowhere to
 * step, so it waits out the kernel's release lag (up to about a second for a socket another process
 * has only just closed) on that same port. A window recovers by stepping, so a second pass only
 * covers a listener that released its socket between passes.
 */
final class RpcBinder {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    /** Retry budget for a listener that cannot move. */
    private static final int SINGLE_CANDIDATE_PASSES = 20;

    /** Retry budget for a listener that can step to its next candidate. */
    private static final int WINDOW_PASSES = 2;

    private static final long PASS_DELAY_MS = 250;

    private RpcBinder() {}

    /**
     * Binds the first candidate of {@code bootstrap}'s channel that accepts the connection.
     *
     * @param stopping polled between attempts; a stop abandons the bind rather than failing it
     * @return the bound channel and its port, or null when a stop was requested first
     * @throws Exception when no candidate could be bound
     */
    static Bound bind(
            ServerBootstrap bootstrap,
            InetAddress address,
            int[] candidates,
            BooleanSupplier stopping)
            throws Exception {
        int passes = candidates.length == 1 ? SINGLE_CANDIDATE_PASSES : WINDOW_PASSES;
        Exception lastBindError = null;
        for (int pass = 0; pass < passes && !stopping.getAsBoolean(); pass++) {
            Attempt attempt = bindOnce(bootstrap, address, candidates, stopping);
            if (attempt.channel() != null) {
                return new Bound(attempt.channel(), attempt.port());
            }
            lastBindError = attempt.error();
            if (attempt.interrupted()) {
                break;
            }
            if (pass + 1 < passes) {
                InterruptedException pause = pauseBeforeRetry();
                if (pause != null) {
                    lastBindError = pause;
                    break;
                }
            }
        }
        if (stopping.getAsBoolean()) {
            // Stop requested while retries were pending: a normal shutdown, not a bind failure.
            LOGGER.finer("[rpc] bind retries abandoned: stopping");
            return null;
        }
        throw lastBindError != null
                ? lastBindError
                : new IllegalStateException("bind failed without exception");
    }

    /** Tries every candidate once, stopping at the first that binds. */
    private static Attempt bindOnce(
            ServerBootstrap bootstrap,
            InetAddress address,
            int[] candidates,
            BooleanSupplier stopping)
            throws Exception {
        Exception lastCandidateError = null;
        for (int candidate : candidates) {
            if (stopping.getAsBoolean()) {
                return Attempt.stopped();
            }
            ChannelFuture bindFuture = null;
            try {
                bindFuture = bootstrap.bind(new InetSocketAddress(address, candidate));
                bindFuture.sync();
                return Attempt.bound(bindFuture.channel(), candidate);
            } catch (InterruptedException e) {
                // Keep the flag, and close any channel the bind produced before it failed.
                Thread.currentThread().interrupt();
                closeQuietly(bindFuture);
                return Attempt.interrupted(e);
            } catch (Exception e) {
                if (!isCauseBindException(e)) {
                    throw e;
                }
                // The candidate is taken; the next one may be free.
                lastCandidateError = e;
            }
        }
        return Attempt.failed(lastCandidateError);
    }

    /** Waits out the kernel's release lag; returns the interruption when one happened. */
    private static InterruptedException pauseBeforeRetry() {
        try {
            Thread.sleep(PASS_DELAY_MS);
            return null;
        } catch (InterruptedException e) {
            // The caller propagates this as a bind error; the flag must survive it.
            Thread.currentThread().interrupt();
            return e;
        }
    }

    private static void closeQuietly(ChannelFuture future) {
        if (future != null) {
            future.channel().close().addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
    }

    private static boolean isCauseBindException(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof BindException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** One pass over the candidate ports. */
    private record Attempt(Channel channel, int port, boolean interrupted, Exception error) {

        static Attempt bound(Channel channel, int port) {
            return new Attempt(channel, port, false, null);
        }

        static Attempt interrupted(InterruptedException error) {
            return new Attempt(null, 0, true, error);
        }

        static Attempt stopped() {
            return new Attempt(null, 0, true, null);
        }

        static Attempt failed(Exception error) {
            return new Attempt(null, 0, false, error);
        }
    }

    /** A successful bind. */
    record Bound(Channel channel, int port) {}
}
