package io.xlite.daemon.app.net.api.http.client;

import com.google.gson.JsonObject;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Height-staleness logging: a backend that stops answering for a coin must
 * be LOUD exactly twice per episode (first miss, recovery), and a null or
 * malformed /height response must count against every coin instead of
 * freezing all heights in total silence. Steady-state success stays quiet.
 */
class HTTPClientHeightStalenessTest {

    private static class StubHeightsClient extends HTTPClient {
        private String heightsJson;

        StubHeightsClient() {
            super(2);
        }

        void serveHeights(String jsonOrNull) {
            this.heightsJson = jsonOrNull;
        }

        @Override
        String executeRequest(String endpoint, JsonObject params) {
            if ("/height".equals(endpoint)) {
                return heightsJson;
            }
            return null;
        }
    }

    private static class CollectingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        List<String> warningsFor(String ticker) {
            List<String> out = new ArrayList<>();
            for (LogRecord r : records) {
                if (r.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()
                        && r.getMessage() != null && r.getMessage().contains(ticker)) {
                    out.add(r.getMessage());
                }
            }
            return out;
        }
    }

    private final StubHeightsClient client = new StubHeightsClient();
    private CoinInstance block;
    private CollectingHandler handler;
    private Logger globalLog;
    private int savedHeight;
    private final Map<CoinInstance, Integer> savedStreaks = new LinkedHashMap<>();

    @BeforeEach
    void captureLogsAndSnapshot() {
        block = CoinInstance.getInstance(CoinTicker.BLOCKNET);
        assertNotNull(block);
        savedHeight = CoinInstance.getBlockCountByTicker(CoinTicker.BLOCKNET);
        // getAllBlockCounts() touches every live singleton: snapshot then
        // reset all so streak counts are exact regardless of suite ordering,
        // and no other test's streaks leak out through us.
        savedStreaks.clear();
        for (CoinInstance ci : CoinInstance.getCoinInstances()) {
            savedStreaks.put(ci, ci.getUpdateFailures());
            ci.resetUpdateFailures();
        }
        globalLog = LogManager.getLogManager().getLogger(Logger.GLOBAL_LOGGER_NAME);
        handler = new CollectingHandler();
        handler.setLevel(java.util.logging.Level.ALL);
        globalLog.addHandler(handler);
    }

    @AfterEach
    void releaseLogsAndRestore() {
        globalLog.removeHandler(handler);
        client.close();
        for (Map.Entry<CoinInstance, Integer> e : savedStreaks.entrySet()) {
            e.getKey().resetUpdateFailures();
            for (int i = 0; i < e.getValue(); i++) {
                e.getKey().incrementUpdateFailures();
            }
        }
        block.addBlockCount(CoinTicker.BLOCKNET, savedHeight);
    }

    @Test
    void testFirstMissWarnsWithTickerAndStreak() {
        client.serveHeights("{\"result\":{}}");
        client.getAllBlockCounts();
        assertEquals(1, block.getUpdateFailures());
        List<String> warnings = handler.warningsFor("BLOCK");
        assertEquals(1, warnings.size(), "first miss must warn once, got " + warnings);
        assertTrue(warnings.get(0).contains("streak 1"), warnings.get(0));
    }

    @Test
    void testOngoingMissesStayQuietAfterFirst() {
        client.serveHeights("{\"result\":{}}");
        for (int i = 0; i < 7; i++) {
            client.getAllBlockCounts();
        }
        assertEquals(7, block.getUpdateFailures());
        List<String> warnings = handler.warningsFor("BLOCK");
        assertEquals(1, warnings.size(), "only the first miss warns, got " + warnings);
        assertTrue(warnings.get(0).contains("streak 1"));
    }

    @Test
    void testNullHeightResponseCountsAgainstEveryCoin() {
        client.serveHeights(null);
        client.getAllBlockCounts();
        for (CoinInstance ci : CoinInstance.getCoinInstances()) {
            assertEquals(1, ci.getUpdateFailures(),
                    "null /height must count, not early-return: " + ci.getTicker());
        }
        assertFalse(handler.warningsFor("BLOCK").isEmpty());
    }

    @Test
    void testMalformedHeightResponseCountsAgainstEveryCoin() {
        client.serveHeights("not json at all {{{");
        client.getAllBlockCounts();
        for (CoinInstance ci : CoinInstance.getCoinInstances()) {
            assertEquals(1, ci.getUpdateFailures(),
                    "malformed /height must count, not abort: " + ci.getTicker());
        }
        assertFalse(handler.warningsFor("BLOCK").isEmpty());
    }

    @Test
    void testWrongTypedHeightValueCountsAsMiss() {
        client.serveHeights("{\"result\":{\"BLOCK\":\"bogus\"}}");
        client.getAllBlockCounts();
        assertEquals(1, block.getUpdateFailures(),
                "wrong-typed height must count, not abort the loop");
        assertFalse(handler.warningsFor("BLOCK").isEmpty());
    }

    @Test
    void testRecoveryWarnsWithStreakAndFreshHeight() {
        client.serveHeights("{\"result\":{}}");
        client.getAllBlockCounts();
        client.getAllBlockCounts();
        client.serveHeights("{\"result\":{\"BLOCK\":4790800}}");
        client.getAllBlockCounts();
        assertEquals(0, block.getUpdateFailures());
        assertEquals(4790800, CoinInstance.getBlockCountByTicker(CoinTicker.BLOCKNET));
        List<String> warnings = handler.warningsFor("BLOCK");
        boolean recovered = false;
        for (String w : warnings) {
            if (w.contains("recovered after 2")) {
                recovered = true;
            }
        }
        assertTrue(recovered, "recovery must name the ended streak: " + warnings);
    }

    @Test
    void testHealthySuccessStaysQuiet() {
        client.serveHeights("{\"result\":{\"BLOCK\":4790800}}");
        client.getAllBlockCounts();
        assertEquals(0, block.getUpdateFailures());
        assertTrue(handler.warningsFor("BLOCK").isEmpty(), "steady state must not warn");
    }
}
