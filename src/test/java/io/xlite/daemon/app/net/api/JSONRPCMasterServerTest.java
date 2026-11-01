package io.xlite.daemon.app.net.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.xlite.daemon.app.util.ConfigHelper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Pins the master listener's port contract: it binds the first free port of its window, records the
 * port that actually bound before it announces readiness, and leaves the configuration alone when
 * no port of the window is free.
 *
 * <p>The record matters as much as the bind. Readiness is announced by a log line that the GUI
 * reacts to, and the GUI then reads the configuration to find the endpoint: a port written after
 * that line would make the first connection of every start go to the old port.
 */
class JSONRPCMasterServerTest {

    @TempDir static Path tempConfigDir;

    /** Restored after the class so a later class never sees a deleted path. */
    private static String previousConfigDir;

    @BeforeAll
    static void isolateConfig() {
        previousConfigDir = ConfigHelper.getConfigDir();
        ConfigHelper.setConfigDir(tempConfigDir.toString());
    }

    @AfterAll
    static void restoreConfig() {
        ConfigHelper.setConfigDir(previousConfigDir);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Holds a port the way an unrelated process would: listening, no reuse. */
    private static ServerSocket hold(int port) throws Exception {
        return new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
    }

    /** A master configuration on disk, exactly as the installer or the GUI leaves it. */
    private static ConfigHelper masterConfig(int port) {
        ConfigHelper config = new ConfigHelper(ConfigHelper.MASTER_TICKER);
        config.setRpcEnabled(true);
        config.setRpcUsername("test-user");
        config.setRpcPassword("test-password");
        config.setRpcPort(port);
        config.writeConfig();
        return config;
    }

    /** The port a fresh read of the configuration finds. */
    private static int recordedPort() {
        return new ConfigHelper(ConfigHelper.MASTER_TICKER).getMasterRpcPort();
    }

    @Test
    @DisplayName("a held port is stepped over, and the port that bound is recorded")
    void testHeldPortIsSteppedOverAndRecorded() throws Exception {
        int held = freePort();
        try (ServerSocket holder = hold(held)) {
            ConfigHelper config = masterConfig(held);
            JSONRPCMasterServer server = new JSONRPCMasterServer(config, 3);
            server.start();
            try {
                assertTrue(server.awaitBound(5000), "the master must step to a free candidate");
                assertNotEquals(held, server.port(), "the held port cannot be reported as bound");
                assertTrue(
                        server.port() > held && server.port() <= held + 3,
                        "stepped port " + server.port() + " is outside the window");
                assertEquals(
                        server.port(),
                        recordedPort(),
                        "the bound port must be on disk before readiness is announced");
                try (Socket probe = new Socket()) {
                    probe.connect(new InetSocketAddress("127.0.0.1", server.port()), 2000);
                    assertTrue(probe.isConnected(), "the reported port must accept connections");
                }
            } finally {
                server.deinit();
                server.join(5000);
            }
        }
    }

    @Test
    @DisplayName("a free preferred port binds unchanged and is recorded as-is")
    void testFreePreferredPortIsUsedAsIs() throws Exception {
        int preferred = freePort();
        ConfigHelper config = masterConfig(preferred);
        JSONRPCMasterServer server = new JSONRPCMasterServer(config, 3);
        server.start();
        try {
            assertTrue(server.awaitBound(5000), "the preferred port was free");
            assertEquals(preferred, server.port(), "a free preferred port must be taken as-is");
            assertEquals(preferred, recordedPort(), "the recorded port must not drift");
        } finally {
            server.deinit();
            server.join(5000);
        }
    }

    @Test
    @DisplayName("an exhausted window leaves no listener and rewrites nothing")
    void testExhaustedWindowLeavesNoListener() throws Exception {
        int base = freePort();
        try (ServerSocket first = hold(base);
                ServerSocket second = hold(base + 1);
                ServerSocket third = hold(base + 2)) {
            ConfigHelper config = masterConfig(base);
            JSONRPCMasterServer server = new JSONRPCMasterServer(config, 2);
            server.start();
            try {
                assertFalse(server.awaitBound(3000), "no candidate of the window was free");
                assertEquals(0, server.port(), "a listener that never bound has no port");
                assertEquals(base, recordedPort(), "a failed bind must not rewrite the port");
            } finally {
                server.deinit();
                server.join(5000);
            }
        }
    }

    @Test
    @DisplayName("the candidate window cannot be moved by a caller")
    void testCandidateWindowIsCopied() {
        int base = 41000;
        JSONRPCMasterServer server = new JSONRPCMasterServer(masterConfig(base), 3);
        int[] expected = server.candidatePorts();
        assertEquals(4, expected.length, "the window includes the preferred port");
        assertEquals(base, expected[0], "the preferred port comes first");

        int[] returned = server.candidatePorts();
        Arrays.fill(returned, 1);

        assertArrayEquals(
                expected, server.candidatePorts(), "a caller must not be able to move the window");
    }
}
