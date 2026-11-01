package io.xlite.daemon.app.net;

import java.util.Arrays;
import java.util.List;

public enum CoinTicker {
    BLOCKNET,

    BITCOIN,
    BITCOIN_CASH,
    BITCORE,
    LITECOIN,
    DASHCOIN,
    DIGIBYTE,
    DOGECOIN,
    SYSCOIN,
    PIVX,
    RAVENCOIN,
    UNOBTANIUM,
    PKOIN,
    VERGE;

    /**
     * List of supported coins.
     *
     * @return Supported coins
     */
    public static List<CoinTicker> coins() {
        return Arrays.asList(
                BLOCKNET,
                BITCOIN,
                BITCOIN_CASH,
                BITCORE,
                LITECOIN,
                DASHCOIN,
                DIGIBYTE,
                DOGECOIN,
                SYSCOIN,
                PIVX,
                UNOBTANIUM,
                PKOIN,
                RAVENCOIN,
                VERGE);
    }
}
