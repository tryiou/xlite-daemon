package io.xlite.daemon.app.testing;

import io.xlite.daemon.app.App;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Keeps the whole suite off the network.
 *
 * <p>Several tests initialise a coin, which starts the real provider session. Point it at a closed
 * loopback port before the first test class runs, so no test dials the built-in production host and
 * no test depends on that host being reachable. Tests that need provider data mock the client
 * instead.
 */
public class LoopbackProviderExtension implements BeforeAllCallback {

    /** Port 1 is never served: a dial fails immediately instead of hanging. */
    private static final String CLOSED_LOOPBACK = "ws://127.0.0.1:1";

    @Override
    public void beforeAll(ExtensionContext context) {
        App.setElectrumEndpoint(CLOSED_LOOPBACK);
    }
}
