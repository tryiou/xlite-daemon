package io.xlite.daemon.app.net.api;

import io.xlite.daemon.app.net.CoinInstance;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

public class JSONRPCServerPortGateTest {

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    public void testPortBindable_TrueOnFreePort() throws Exception {
        int port = freePort();
        assertTrue(JSONRPCServer.portBindable(port));
    }

    @Test
    public void testPortBindable_FalseWhileHeld() throws Exception {
        int port = freePort();
        try (ServerSocket holder = new ServerSocket(port, 1,
                InetAddress.getByName("0.0.0.0"))) {
            // A plain listener without SO_REUSEADDR must make the gated
            // probe report the port as not bindable.
            assertFalse(JSONRPCServer.portBindable(port));
        }
        assertTrue(JSONRPCServer.portBindable(port));
    }

    @Test
    public void testAwaitPortBindable_TimesOutWhileHeld() throws Exception {
        int port = freePort();
        try (ServerSocket holder = new ServerSocket(port, 1,
                InetAddress.getByName("0.0.0.0"))) {
            long t0 = System.currentTimeMillis();
            assertFalse(JSONRPCServer.awaitPortBindable(port, 400));
            assertTrue(System.currentTimeMillis() - t0 >= 350);
        }
    }

    @Test
    public void testAwaitPortBindable_ReturnsOnceReleased() throws Exception {
        int port = freePort();
        try (ServerSocket holder = new ServerSocket(port, 1,
                InetAddress.getByName("0.0.0.0"))) {
            // Release after a short delay.
            new Thread(() -> {
                try {
                    Thread.sleep(250);
                    holder.close();
                } catch (Exception ignored) {
                }
            }).start();
            long t0 = System.currentTimeMillis();
            assertTrue(JSONRPCServer.awaitPortBindable(port, 3000));
            // Prompt-return proof: success lands well under 2s once released,
            // while a gate that misses the release rides to its 3s deadline.
            assertTrue(System.currentTimeMillis() - t0 < 2000);
        }
    }

    @Test
    public void testAwaitBound_PromptReturnWhenStopping() throws Exception {
        // A stop request is a terminal state for the wait: burning the full
        // budget and stamping a bind failure on a benign shutdown race is
        // neither prompt nor truthful.
        JSONRPCServer server = new JSONRPCServer(mock(CoinInstance.class), freePort());
        server.deinit();
        long t0 = System.currentTimeMillis();
        assertFalse(server.awaitBound(5000));
        assertTrue(System.currentTimeMillis() - t0 < 1000);
    }

    @Test
    public void testAwaitBound_RidesToDeadlineWhilePending() throws Exception {
        // While no terminal state is reached, the wait must keep its budget
        // so a slow but successful rebind is not misread as a failure.
        JSONRPCServer server = new JSONRPCServer(mock(CoinInstance.class), freePort());
        long t0 = System.currentTimeMillis();
        assertFalse(server.awaitBound(400));
        assertTrue(System.currentTimeMillis() - t0 >= 350);
    }
}
