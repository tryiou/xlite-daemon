package io.xlite.daemon.app.net.electrumx;

import java.net.URI;

/** Builds the per-coin WebSocket endpoint from one configured base URL. */
public final class ElectrumEndpoints {

    private ElectrumEndpoints() {}

    /**
     * {@code http://hub:5000/} → {@code ws://hub:5000/ws/BLOCK}.
     *
     * <p>Accepts an already-WebSocket base too, so a deployment can publish {@code wss://}
     * directly. The ticker is upper-cased because the hub dispatches on it.
     */
    public static URI websocket(String base, String ticker) {
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("electrum endpoint is empty");
        }
        String text = base.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.startsWith("https://")) {
            text = "wss://" + text.substring("https://".length());
        } else if (text.startsWith("http://")) {
            text = "ws://" + text.substring("http://".length());
        }
        if (!text.startsWith("ws://") && !text.startsWith("wss://")) {
            throw new IllegalArgumentException(
                    "electrum endpoint must be http(s):// or ws(s)://: " + base);
        }
        return URI.create(text + "/ws/" + ticker.toUpperCase(java.util.Locale.ROOT));
    }
}
