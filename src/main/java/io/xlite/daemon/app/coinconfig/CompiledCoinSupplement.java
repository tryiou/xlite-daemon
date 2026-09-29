package io.xlite.daemon.app.coinconfig;

import java.util.Map;

/**
 * The per-coin values a config-driven parameter set needs that the
 * blockchain-configuration-files repository does not carry: max supply,
 * wire protocol version, network id string and dust threshold.
 *
 * <p>Values were lifted verbatim from the per-coin hardcoded parameter classes
 * these replace, and from bitcoinj's MainNetParams for BTC. Those classes
 * have since been deleted, so this table is now their only remaining record.
 * They are consensus-era constants that essentially never move — unlike the
 * bcf-carried data (prefixes, fees), which is exactly why they live here and
 * not in a conf file.</p>
 */
public final class CompiledCoinSupplement {

    /** A coin's non-bcf-carried constants. */
    public static final class Supplement {
        public final long maxMoneyCoins;
        public final int protocolVersion;
        public final String id;
        public final String chainName;

        public Supplement(long maxMoneyCoins, int protocolVersion, String id, String chainName) {
            this.maxMoneyCoins = maxMoneyCoins;
            this.protocolVersion = protocolVersion;
            this.id = id;
            this.chainName = chainName;
        }
    }

    private static final Map<String, Supplement> BY_TICKER = Map.ofEntries(
            Map.entry("XVG", new Supplement(16_555_000_000L, 90009, "XVG", "main")),
            // Blocknet: supply, protocol version, network id and payment protocol
            // id are consensus constants the configuration repository does not
            // carry, so they stay compiled in. Its prefixes, divisor, fees, port
            // and dust floor are NOT here — they come from its configuration file
            // like any other coin's.
            Map.entry("BLOCK", new Supplement(43_199_500L, 70_712, "BLOCK", "main")),
            Map.entry("BTC", new Supplement(21_000_000L, 70012, "org.bitcoin.production", "main")),
            Map.entry("BCH", new Supplement(21_000_000L, 70012, "BCH", "main")),
            Map.entry("DASH", new Supplement(22_000_000L, 70210, "DASH", "main")),
            Map.entry("DGB", new Supplement(2_000_000_000L, 70002, "DGB", "main")),
            Map.entry("DOGE", new Supplement(2_000_000_000L, 70004, "DOGE", "main")),
            Map.entry("LTC", new Supplement(84_000_000L, 70015, "LTC", "main")),
            Map.entry("PIVX", new Supplement(100_000_000L, 70007, "PIVX", "main")),
            Map.entry("PKOIN", new Supplement(21_000_000L, 70031, "PKOIN", "main")),
            Map.entry("RVN", new Supplement(100_000_000L, 70026, "RVN", "main")),
            Map.entry("SYS", new Supplement(888_000_000L, 70227, "SYS", "main")),
            Map.entry("UNO", new Supplement(250_000L, 70002, "UNO", "main"))
    );

    /**
     * Test seam: supplements registered here shadow the compiled table, so a
     * test can construct a coin whose COIN factor is not 1e8. Without it the
     * money math is only reachable for 8-decimal coins, where a hardcoded
     * 1e8 divisor is indistinguishable from the configured one.
     *
     * <p>Held per-thread so a parallel test run cannot have one class's
     * overrides observed by another, and so the value is unreachable once the
     * owning thread dies. It is <em>not</em> self-clearing: surefire reuses
     * one thread across classes, so a test that registers an override and does
     * not call {@link #resetSupplementsForTest()} does carry it into the next
     * class on that thread. The reset in
     * {@code CoinInstanceDivisorTest}'s {@code @AfterEach} is what clears it.
     * Nothing in the daemon sets it.
     */
    private static final ThreadLocal<Map<String, Supplement>> testOverrides =
            ThreadLocal.withInitial(Map::of);

    /**
     * Registers supplements that shadow the compiled table.
     *
     * @param overrides ticker to supplement; replaces the compiled entry
     */
    public static void loadSupplementsForTest(Map<String, Supplement> overrides) {
        testOverrides.set(Map.copyOf(overrides));
    }

    /** Visible for tests only. */
    public static void resetSupplementsForTest() {
        testOverrides.remove();
    }

    /**
     * @param ticker manifest ticker (case-sensitive, e.g. {@code LTC})
     * @throws IllegalArgumentException for tickers with no compiled entry
     */
    public static Supplement forTicker(String ticker) {
        Supplement s = testOverrides.get().getOrDefault(ticker, BY_TICKER.get(ticker));
        if (s == null)
            throw new IllegalArgumentException(
                    "no compiled supplement for ticker '" + ticker
                            + "' — the coin cannot run without its non-config constants");
        return s;
    }

    public static boolean supports(String ticker) {
        return testOverrides.get().containsKey(ticker) || BY_TICKER.containsKey(ticker);
    }

    private CompiledCoinSupplement() {
    }
}
