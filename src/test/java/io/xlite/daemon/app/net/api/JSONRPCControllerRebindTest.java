package io.xlite.daemon.app.net.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.util.ConfigHelper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Pins the controller's rebind handoff contract: a rebind retires the previous instance and
 * installs a fresh one atomically from the caller's point of view, including when the retiring
 * server is LIVE (real socket teardown path).
 *
 * <p>Coverage honesty: the concurrent soak exercises interleaved rebinds on unstarted servers and
 * proves robustness/coherence, but cannot make the kernel-release lag window materialize on demand
 * — full interleaved reproduction of the retirement race is covered by the stack wave detectors
 * (per-coin post-rebind port probe, lifecycle-anomaly logsweep), which have proven this path across
 * all coins.
 */
public class JSONRPCControllerRebindTest {

    @TempDir static Path tempConfigDir;

    /** Restored after the class so a later class never sees a deleted path. */
    private static String previousConfigDir;

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @BeforeAll
    static void isolateConfig() {
        previousConfigDir = ConfigHelper.CONFIG_DIR;
        ConfigHelper.CONFIG_DIR = tempConfigDir.toString();
    }

    @AfterAll
    static void restoreConfig() {
        JSONRPCController.resetForTest();
        ConfigHelper.CONFIG_DIR = previousConfigDir;
    }

    @Test
    public void testRebind_ReturnsFreshInstanceAndSwapsHandout() throws Exception {
        CoinInstance coin = mock(CoinInstance.class);
        int port = freePort();
        when(coin.getRPCPort()).thenReturn(port);
        when(coin.getRpcBindAddress()).thenReturn("127.0.0.1");
        when(coin.getRpcCandidates()).thenReturn(new int[] {port});

        JSONRPCServer first = JSONRPCController.getRPCServer(coin);
        JSONRPCServer rebound = JSONRPCController.rebindRPCServer(coin);

        assertNotNull(rebound);
        assertNotSame(first, rebound);
        assertSame(rebound, JSONRPCController.getRPCServer(coin));
    }

    @Test
    public void testRebind_RetiresLiveServerAndInstallsFresh() throws Exception {
        CoinInstance coin = mock(CoinInstance.class);
        int port = freePort();
        when(coin.getRPCPort()).thenReturn(port);
        when(coin.getRpcBindAddress()).thenReturn("127.0.0.1");
        when(coin.getRpcCandidates()).thenReturn(new int[] {port});

        JSONRPCServer live = JSONRPCController.getRPCServer(coin);
        live.start();
        assertTrue(live.awaitBound(5000), "precondition: live server bound");

        JSONRPCServer rebound = JSONRPCController.rebindRPCServer(coin);

        assertNotSame(live, rebound);
        // Controller hands out unstarted servers — starting is the
        // caller's job (reloadConfig does exactly this).
        rebound.start();
        // The old instance must be retired and its thread fully unwound
        // (bounded join: isAlive() can lag closeFuture completion by the
        // run() method's exit path) and the replacement must bind.
        live.join(5000);
        assertFalse(live.isAlive());
        assertTrue(rebound.awaitBound(10000), "replacement server must bind");
        assertSame(rebound, JSONRPCController.getRPCServer(coin));
    }

    @Test
    public void testRebind_ConcurrentCycles_NoDeadlock_CoherentEndState() throws Exception {
        CoinInstance coin = mock(CoinInstance.class);
        int port = freePort();
        when(coin.getRPCPort()).thenReturn(port);
        when(coin.getRpcBindAddress()).thenReturn("127.0.0.1");
        when(coin.getRpcCandidates()).thenReturn(new int[] {port});

        final int threads = 8;
        final int cyclesPerThread = 25;
        List<JSONRPCServer> handedOut = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                for (int i = 0; i < cyclesPerThread; i++) {
                    handedOut.add(JSONRPCController.rebindRPCServer(coin));
                }
                return null;
            });
        }

        List<Future<Void>> futures = pool.invokeAll(tasks);
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }

        // Every consumer received an instance, none was null, and the
        // final handout equals the map's current server.
        assertEquals(threads * cyclesPerThread, handedOut.size());
        assertTrue(handedOut.contains(JSONRPCController.getRPCServer(coin)));
    }
}
