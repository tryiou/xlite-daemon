package io.xlite.daemon.app.net;

import com.google.common.collect.HashBiMap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class CoinTickerUtils {
    private static HashBiMap<CoinTicker, String> tickers;

    static {
        tickers = HashBiMap.create();

        tickers.put(CoinTicker.BLOCKNET, "BLOCK");
        tickers.put(CoinTicker.BLOCKNET_TESTNET5, "TBLOCK");
        tickers.put(CoinTicker.BITCOIN, "BTC");
        tickers.put(CoinTicker.LITECOIN, "LTC");
        tickers.put(CoinTicker.DASHCOIN, "DASH");
        tickers.put(CoinTicker.DOGECOIN, "DOGE");
        tickers.put(CoinTicker.SYSCOIN, "SYS");
        tickers.put(CoinTicker.PIVX, "PIVX");

        tickers.put(CoinTicker.DIGIBYTE, "DGB");
//		tickers.put(CoinTicker.BITCOIN_CASH, "BCH");
        tickers.put(CoinTicker.RAVENCOIN, "RVN");

        tickers.put(CoinTicker.UNOBTANIUM, "UNO");
        tickers.put(CoinTicker.PKOIN, "PKOIN");

    }

    public static String tickerToString(CoinTicker ticker) {
        return tickers.get(ticker);
    }

    public static CoinTicker stringToTicker(String string) {
        return tickers.inverse().get(string);
    }

    public static Set<CoinTicker> getNetworkTickers() {
        return new HashSet<>(Arrays.asList(CoinTicker.BLOCKNET, CoinTicker.BLOCKNET_TESTNET5));
    }

    /**
     * Active (mainnet, wallet-capable) tickers, derived from the canonical
     * supported list {@link CoinTicker#coins()} minus testnets. A delisted
     * coin is excluded from {@code coins()} (its enum variant may linger,
     * e.g. BITCOIN_CASH); to (re-)add one, restore its {@code coins()}
     * entry, string mapping, supplement row and CoinInstance init case
     * together (the source-of-truth test pins the set, mapping and
     * supplement; wire the init case by hand).
     */
    public static CoinTicker[] getActiveTickers() {
        // Name-pattern exclusion (not an explicit variant comparison) so a
        // future TESTNET* is excluded by default rather than silently listed.
        return CoinTicker.coins().stream()
                .filter(t -> !t.name().contains("TESTNET"))
                .toArray(CoinTicker[]::new);
    }

    public static boolean tickerExists(String string) {
        return tickers.inverse().containsKey(string);
    }

    public static boolean isActiveTicker(CoinTicker ticker) {
        for (CoinTicker t : getActiveTickers()) {
            if (ticker == t) {
                return true;
            }
        }
        return false;
    }
}
