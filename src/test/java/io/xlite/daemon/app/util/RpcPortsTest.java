package io.xlite.daemon.app.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the listener-port rule: a port derived from the configuration
 * repository, offset so a core wallet cannot collide, and a short candidate
 * window for when the host already holds it.
 */
class RpcPortsTest {

    @Test
    void testXlitePort_OffsetsAboveTheCoinDaemonsOwnPort() {
        assertEquals(41417, RpcPorts.xlitePort(41414)); // BLOCK
        assertEquals(8335, RpcPorts.xlitePort(8332));   // BTC
        assertEquals(10001, RpcPorts.xlitePort(9998));  // DASH
        assertEquals(51577, RpcPorts.xlitePort(51574)); // PIVX
        assertEquals(37074, RpcPorts.xlitePort(37071)); // PKOIN
    }

    @Test
    void testXlitePort_StepsDownWhenTheOffsetLeavesTheRange() {
        // UNO's core wallet listens on 65535 and +3 would be invalid.
        assertEquals(65532, RpcPorts.xlitePort(65535));
        assertEquals(65530, RpcPorts.xlitePort(65533));
        assertEquals(65531, RpcPorts.xlitePort(65534));
    }

    @Test
    void testXlitePort_RejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> RpcPorts.xlitePort(0));
        assertThrows(IllegalArgumentException.class, () -> RpcPorts.xlitePort(-1));
        assertThrows(IllegalArgumentException.class, () -> RpcPorts.xlitePort(65536));
    }

    @Test
    void testCandidates_StartAtBaseAndStayOrderedAndDistinct() {
        int[] ports = RpcPorts.candidates(41417, RpcPorts.WINDOW);

        assertEquals(RpcPorts.WINDOW + 1, ports.length);
        assertEquals(41417, ports[0]);
        assertEquals(41417 + RpcPorts.WINDOW, ports[ports.length - 1]);
        Set<Integer> distinct = new LinkedHashSet<>();
        for (int p : ports) {
            assertTrue(distinct.add(p), "duplicate candidate " + p);
        }
    }

    @Test
    void testCandidates_ZeroWindowYieldsOnlyTheConfiguredPort() {
        // A port read from a config file is the operator's choice: the
        // listener may retry it, but must never move to a neighbour.
        assertArrayEquals(new int[]{41417}, RpcPorts.candidates(41417, 0));
    }

    @Test
    void testCandidates_StopAtTheTopOfTheRange() {
        int[] ports = RpcPorts.candidates(65530, RpcPorts.WINDOW);

        assertEquals(65530, ports[0]);
        assertEquals(65535, ports[ports.length - 1]);
        assertEquals(6, ports.length);
    }

    @Test
    void testCandidates_NeverLeaveTheValidRangeOrDuplicate() {
        for (int base = 1; base < 65535; base += 97) {
            int[] ports = RpcPorts.candidates(base, RpcPorts.WINDOW);
            Set<Integer> seen = new LinkedHashSet<>();
            for (int p : ports) {
                assertTrue(p >= 1 && p <= 65535, "out of range " + p + " from base " + base);
                assertTrue(seen.add(p), "duplicate " + p + " from base " + base);
            }
            assertEquals(base, ports[0]);
        }
    }

    @Test
    void testCandidates_RejectsInvalidBase() {
        assertThrows(IllegalArgumentException.class, () -> RpcPorts.candidates(0, 10));
        assertThrows(IllegalArgumentException.class, () -> RpcPorts.candidates(65536, 10));
    }
}
