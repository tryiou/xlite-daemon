package io.xlite.daemon.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Listener-port rule: BCF {@code Port} + 3, with a short upward window when the host already holds
 * that port. Above 65532 the offset is subtracted (UNO 65535 → 65532). Binding is the availability
 * test; there is no probe.
 */
public final class RpcPorts {

    /** Candidates a stepping listener may try. */
    public static final int WINDOW = 10;

    private static final int OFFSET = 3;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /** Scan headroom: excluded ports are skipped without being returned. */
    private static final int MAX_SCAN = 200;

    private RpcPorts() {}

    /** First port to try for a coin whose BCF port is {@code bcfPort}. */
    public static int xlitePort(int bcfPort) {
        if (bcfPort < MIN_PORT || bcfPort > MAX_PORT) {
            throw new IllegalArgumentException("BCF port " + bcfPort + " is outside 1..65535");
        }
        int candidate = bcfPort + OFFSET;
        return candidate > MAX_PORT ? bcfPort - OFFSET : candidate;
    }

    /** Ordered, distinct candidates from {@code basePort}, skipping {@code excluded}. */
    public static int[] candidates(int basePort, int window, Set<Integer> excluded) {
        if (basePort < MIN_PORT || basePort > MAX_PORT) {
            throw new IllegalArgumentException("base port " + basePort + " is outside 1..65535");
        }
        int wanted = Math.max(0, window) + 1;
        List<Integer> ports = new ArrayList<>(wanted);
        int limit = Math.min(MAX_PORT, basePort + MAX_SCAN);
        for (int port = basePort; port <= limit && ports.size() < wanted; port++) {
            if (!excluded.contains(port)) {
                ports.add(port);
            }
        }
        return ports.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Candidates with nothing excluded. */
    public static int[] candidates(int basePort, int window) {
        return candidates(basePort, window, Set.of());
    }
}
