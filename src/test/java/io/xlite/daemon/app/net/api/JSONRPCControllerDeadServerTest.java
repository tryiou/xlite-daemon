package io.xlite.daemon.app.net.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.util.RpcPorts;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;

/**
 * A controller handout must always be a server that can still be started.
 *
 * <p>{@code Thread} cannot be restarted, so a listener whose bind failed (or that was
 * deinitialized) is permanently dead. Handing that instance back would make every later {@code
 * start()} a silent no-op and leave the coin's RPC unavailable until the process restarted.
 */
public class JSONRPCControllerDeadServerTest {

    @TempDir static Path tempConfigDir;

    /** Restored after the class so a later class never sees a deleted path. */
    private static String previousConfigDir;

    @BeforeAll
    static void isolateConfig() {
        previousConfigDir = io.xlite.daemon.app.util.ConfigHelper.getConfigDir();
        io.xlite.daemon.app.util.ConfigHelper.setConfigDir(tempConfigDir.toString());
    }

    @AfterAll
    static void restoreConfig() {
        io.xlite.daemon.app.util.ConfigHelper.setConfigDir(previousConfigDir);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static CoinInstance coin(int port, int window) {
        CoinInstance coin = mock(CoinInstance.class);
        when(coin.getRPCPort()).thenReturn(port);
        when(coin.getRpcBindAddress()).thenReturn("127.0.0.1");
        when(coin.getRpcCandidates()).thenReturn(RpcPorts.candidates(port, window));
        return coin;
    }

    @AfterEach
    void reset() {
        JSONRPCController.resetForTest();
    }

    @Test
    public void testTerminatedServerIsReplacedOnNextHandout() throws Exception {
        CoinInstance coin = coin(freePort(), 3);

        JSONRPCServer first = JSONRPCController.getRPCServer(coin);
        first.start();
        assertTrue(first.awaitBound(5000));
        first.deinit();
        first.join(5000);
        assertFalse(first.isAlive(), "precondition: the listener is dead");

        JSONRPCServer second = JSONRPCController.getRPCServer(coin);

        assertNotSame(first, second, "a dead listener must never be handed out again");
        second.start();
        try {
            assertTrue(second.awaitBound(5000), "the replacement must bind");
        } finally {
            second.deinit();
            second.join(5000);
        }
    }

    @Test
    public void testLiveServerIsReused() throws Exception {
        CoinInstance coin = coin(freePort(), 3);

        JSONRPCServer first = JSONRPCController.getRPCServer(coin);
        assertSame(first, JSONRPCController.getRPCServer(coin));

        first.start();
        try {
            assertTrue(first.awaitBound(5000));
            assertSame(
                    first,
                    JSONRPCController.getRPCServer(coin),
                    "a live listener must be reused, not replaced");
        } finally {
            first.deinit();
            first.join(5000);
        }
    }
}
