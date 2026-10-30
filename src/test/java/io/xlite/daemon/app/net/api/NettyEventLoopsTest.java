package io.xlite.daemon.app.net.api;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.xlite.daemon.app.net.CoinInstance;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins the shared-worker-group contract: every listener borrows one group,
 * retiring a listener closes only its own channel, and the group is released
 * exactly once by application shutdown.
 *
 * <p>This is the thread-budget property: a per-listener group cost five
 * threads per coin (60 for twelve coins, 205 for the full set), while one
 * shared group is bounded by the host's core count.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NettyEventLoopsTest {

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static JSONRPCServer server(int port) {
        return new JSONRPCServer(mock(CoinInstance.class), port, "127.0.0.1");
    }

    @Test
    @Order(1)
    public void testSharedGroupIsReusedAcrossCallers() {
        EventLoopGroup first = NettyEventLoops.shared();
        EventLoopGroup second = NettyEventLoops.shared();

        assertNotNull(first);
        assertSame(first, second, "every listener must borrow the same group");
        assertFalse(first.isShuttingDown());
    }

    @Test
    @Order(2)
    public void testRetiringOneListenerLeavesSiblingServing() throws Exception {
        EventLoopGroup group = NettyEventLoops.shared();

        JSONRPCServer retiring = server(freePort());
        JSONRPCServer sibling = server(freePort());
        try {
            retiring.start();
            sibling.start();
            assertTrue(retiring.awaitBound(5000), "precondition: first listener bound");
            assertTrue(sibling.awaitBound(5000), "precondition: second listener bound");

            retiring.deinit();
            retiring.join(5000);

            assertFalse(retiring.isAlive());
            assertTrue(sibling.isAlive());
            assertFalse(group.isShuttingDown(),
                    "retiring one listener must not retire the shared group");
            // The sibling must still accept connections on its own socket.
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", sibling.port()), 2000);
                assertTrue(probe.isConnected());
            }
        } finally {
            sibling.deinit();
            sibling.join(5000);
            retiring.join(5000);
        }
    }

    @Test
    @Order(3)
    public void testPoolSizeIsFixedRegardlessOfListenerCount() throws Exception {
        EventLoopGroup group = NettyEventLoops.shared();
        int children = 0;
        for (Object ignored : group) {
            children++;
        }
        assertEquals(NettyEventLoops.workerThreads(), children,
                "the pool must be sized from the host, not the coin count");

        JSONRPCServer[] servers = new JSONRPCServer[4];
        try {
            for (int i = 0; i < servers.length; i++) {
                servers[i] = server(freePort());
                servers[i].start();
                assertTrue(servers[i].awaitBound(5000), "listener " + i + " must bind");
            }

            int afterStart = 0;
            for (Object ignored : group) {
                afterStart++;
            }
            assertEquals(children, afterStart,
                    "adding listeners must not grow the shared pool");
        } finally {
            for (JSONRPCServer s : servers) {
                if (s != null) {
                    s.deinit();
                    s.join(5000);
                }
            }
        }
    }

    @Test
    @Order(4)
    public void testShutdownTerminatesGroupAndNextUseCreatesFreshOne() throws Exception {
        EventLoopGroup before = NettyEventLoops.shared();

        Future<?> termination = NettyEventLoops.shutdown(0, 2000);
        assertNotNull(termination);
        termination.awaitUninterruptibly(5000);

        assertTrue(before.isShuttingDown());
        assertTrue(before.isTerminated(), "shutdown must terminate every worker");

        EventLoopGroup after = NettyEventLoops.shared();
        assertNotSame(before, after, "the holder must recreate after shutdown");
        assertFalse(after.isShuttingDown());

        // Leave the process in the state the rest of the suite expects: a
        // live, usable group.
        NettyEventLoops.shutdown(0, 2000).awaitUninterruptibly(5000);
        assertFalse(NettyEventLoops.shared().isShuttingDown());
    }
}
