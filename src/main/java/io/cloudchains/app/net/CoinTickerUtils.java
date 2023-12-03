package io.cloudchains.app.net;

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

        // TODO Temporarily disable until supported
//		tickers.put(CoinTicker.DIGIBYTE, "DGB");
//		tickers.put(CoinTicker.BITCOIN_CASH, "BCH");
//      tickers.put(CoinTicker.RAVENCOIN, "RVN");

        tickers.put(CoinTicker.ALQOCOIN, "XLQ");
        // TODO Temporarily disable PHORE and POLIS until supported
//        tickers.put(CoinTicker.POLISCOIN, "POLIS");
//        tickers.put(CoinTicker.PHORECOIN, "PHR");
		tickers.put(CoinTicker.TREZARCOIN, "TZC");
		tickers.put(CoinTicker.BITBAY, "BAY");
		tickers.put(CoinTicker.UNOBTANIUM, "UNO");
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

	public static CoinTicker[] getActiveTickers() {
		return new CoinTicker[] {
				CoinTicker.BLOCKNET,
				CoinTicker.BITCOIN,
				CoinTicker.LITECOIN,
				CoinTicker.DASHCOIN,
				CoinTicker.DOGECOIN,
				CoinTicker.SYSCOIN,
				CoinTicker.PIVX,

                // TODO Temporarily disable until supported
//				CoinTicker.DIGIBYTE,
//				CoinTicker.BITCOIN_CASH,
//              CoinTicker.RAVENCOIN,

                CoinTicker.ALQOCOIN,
                // TODO Temporarily disable PHORE and POLIS until supported
//                CoinTicker.POLISCOIN,
//                CoinTicker.PHORECOIN,
				CoinTicker.TREZARCOIN,
				CoinTicker.BITBAY,
				CoinTicker.UNOBTANIUM
		};
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
