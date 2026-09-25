package io.xlite.daemon.app.net;

import com.google.common.collect.HashBiMap;

public class CoinTickerUtils {
    private static HashBiMap<CoinTicker, String> tickers;

    static {
        tickers = HashBiMap.create();

        tickers.put(CoinTicker.BLOCKNET, "BLOCK");
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

    /**
     * Active (mainnet, wallet-capable) tickers: the canonical supported
     * list {@link CoinTicker#coins()}. There are no testnet variants; a
     * delisted coin is excluded from {@code coins()} (its enum variant may
     * linger, e.g. BITCOIN_CASH). To (re-)add one, restore its
     * {@code coins()} entry, string mapping, supplement row and CoinInstance
     * init case together (the source-of-truth test pins the set, mapping
     * and supplement; wire the init case by hand).
     */
    public static CoinTicker[] getActiveTickers() {
        return CoinTicker.coins().toArray(new CoinTicker[0]);
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
