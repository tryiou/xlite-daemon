package io.cloudchains.app.coinconfig;

import java.util.Map;

/**
 * The per-coin values a config-driven parameter set needs that the
 * blockchain-configuration-files repository does not carry: max supply,
 * wire protocol version, network id string and dust threshold.
 *
 * <p>Values were lifted verbatim from the legacy per-coin parameter classes
 * they replace (BTC from bitcoinj's MainNetParams inheritance). They are
 * consensus-era constants that essentially never move — unlike the bcf-carried
 * data (prefixes, fees), which is exactly why they live here and not in a
 * conf file.</p>
 */
public final class CompiledCoinSupplement {

    /** A coin's non-bcf-carried constants. */
    public static final class Supplement {
        public final long maxMoneyCoins;
        public final long dustSat;
        public final int protocolVersion;
        public final String id;
        public final String chainName;

        public Supplement(long maxMoneyCoins, long dustSat, int protocolVersion, String id, String chainName) {
            this.maxMoneyCoins = maxMoneyCoins;
            this.dustSat = dustSat;
            this.protocolVersion = protocolVersion;
            this.id = id;
            this.chainName = chainName;
        }
    }

    private static final long BTC_DUST = 546; // Transaction.MIN_NONDUST_OUTPUT

    private static final Map<String, Supplement> BY_TICKER = Map.ofEntries(
            Map.entry("BTC", new Supplement(21_000_000L, BTC_DUST, 70012, "org.bitcoin.production", "main")),
            Map.entry("BCH", new Supplement(21_000_000L, BTC_DUST, 70012, "BCH", "main")),
            Map.entry("DASH", new Supplement(22_000_000L, 5460L, 70210, "DASH", "main")),
            Map.entry("DGB", new Supplement(2_000_000_000L, 1000L, 70002, "DGB", "main")),
            Map.entry("DOGE", new Supplement(2_000_000_000L, BTC_DUST, 70004, "DOGE", "main")),
            Map.entry("LTC", new Supplement(84_000_000L, 100_000L, 70015, "LTC", "main")),
            Map.entry("PIVX", new Supplement(100_000_000L, BTC_DUST, 70007, "PIVX", "main")),
            Map.entry("PKOIN", new Supplement(21_000_000L, BTC_DUST, 70031, "PKOIN", "main")),
            Map.entry("RVN", new Supplement(100_000_000L, BTC_DUST, 70026, "RVN", "main")),
            Map.entry("SYS", new Supplement(888_000_000L, 5500L, 70227, "SYS", "main")),
            Map.entry("UNO", new Supplement(250_000L, BTC_DUST, 70002, "UNO", "main"))
    );

    /**
     * @param ticker manifest ticker (case-sensitive, e.g. {@code LTC})
     * @throws IllegalArgumentException for tickers with no compiled entry
     */
    public static Supplement forTicker(String ticker) {
        Supplement s = BY_TICKER.get(ticker);
        if (s == null)
            throw new IllegalArgumentException(
                    "no compiled supplement for ticker '" + ticker
                            + "' — the coin cannot run without its non-config constants");
        return s;
    }

    public static boolean supports(String ticker) {
        return BY_TICKER.containsKey(ticker);
    }

    private CompiledCoinSupplement() {
    }
}
