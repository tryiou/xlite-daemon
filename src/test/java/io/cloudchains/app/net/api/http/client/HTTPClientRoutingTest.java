package io.cloudchains.app.net.api.http.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTPClientRoutingTest {

    static class LegacyModeClient extends HTTPClient {
        LegacyModeClient() {
            super(2);
        }

        @Override
        boolean useEXR() {
            return false;
        }
    }

    static class ExrModeClient extends HTTPClient {
        ExrModeClient() {
            super(2);
        }

        @Override
        boolean useEXR() {
            return true;
        }
    }

    @Test
    public void testShouldUseEXR_LegacyModeNeverRoutesToEXR() {
        HTTPClient client = new LegacyModeClient();
        assertFalse(client.shouldUseEXR("/"));
        assertFalse(client.shouldUseEXR("/height"));
        assertFalse(client.shouldUseEXR("/fees"));
    }

    @Test
    public void testShouldUseEXR_ExrModeRoutesSupportedEndpoints() {
        HTTPClient client = new ExrModeClient();
        assertTrue(client.shouldUseEXR("/"));
        assertTrue(client.shouldUseEXR("/height"));
        assertTrue(client.shouldUseEXR("/fees"));
    }

    @Test
    public void testShouldUseEXR_ExrModeIgnoresUnknownEndpoints() {
        HTTPClient client = new ExrModeClient();
        assertFalse(client.shouldUseEXR("/other"));
        assertFalse(client.shouldUseEXR(""));
    }
}
