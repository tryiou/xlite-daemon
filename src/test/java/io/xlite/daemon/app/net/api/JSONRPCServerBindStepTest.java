package io.xlite.daemon.app.net.api;

import io.xlite.daemon.app.net.CoinInstance;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Pins bind-time port stepping: a port held by another process is skipped, the
 * listener takes the first candidate it can bind, and a single-candidate
 * listener never moves.
 */
public class JSONRPCServerBindStepTest {

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** Holds a port the way an unrelated process would: listening, no reuse. */
    private static ServerSocket hold(int port) throws Exception {
        return new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
    }

    @Test
    public void testSecondCandidateIsUsedWhenTheFirstIsHeld() throws Exception {
        int held = freePort();
        JSONRPCServer server = new JSONRPCServer(mock(CoinInstance.class), held, "127.0.0.1", 3);
        try (ServerSocket ignored = hold(held)) {
            server.start();
            assertTrue(server.awaitBound(5000), "listener must step to a free candidate");
            assertNotEquals(held, server.port(), "the held port must not be reported as bound");
            assertTrue(server.port() > held && server.port() <= held + 3);
            // The listener must actually be reachable on the reported port.
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", server.port()), 2000);
                assertTrue(probe.isConnected());
            }
        } finally {
            server.deinit();
            server.join(5000);
        }
    }

    @Test
    public void testSingleCandidateNeverMoves() throws Exception {
        int held = freePort();
        JSONRPCServer server = new JSONRPCServer(mock(CoinInstance.class), held, "127.0.0.1",
                new int[]{held});
        try (ServerSocket ignored = hold(held)) {
            server.start();
            assertFalse(server.awaitBound(2000), "a single candidate must not be replaced");
            assertEquals(held, server.port(), "reported port must stay the requested one");
        } finally {
            server.deinit();
            server.join(5000);
        }
    }

    @Test
    public void testExhaustedWindowFailsWithoutBinding() throws Exception {
        int held = freePort();
        // Hold the base port plus the whole two-port window.
        try (ServerSocket first = hold(held);
             ServerSocket second = hold(held + 1);
             ServerSocket third = hold(held + 2)) {
            JSONRPCServer server = new JSONRPCServer(mock(CoinInstance.class), held, "127.0.0.1",
                    new int[]{held, held + 1, held + 2});
            try {
                server.start();
                assertFalse(server.awaitBound(3000), "no candidate was free");
                String state = server.lifecycleState();
                assertTrue(state.contains("bindFailed=true"), "state must record the failure: " + state);
                assertTrue(state.contains("channel=null"), "no channel may be left behind: " + state);
                assertFalse(state.contains("bound=true"), "a failed listener must not claim to be bound: " + state);
            } finally {
                server.deinit();
                server.join(5000);
            }
        }
    }
}
