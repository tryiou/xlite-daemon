package io.cloudchains.app.net;

import java.util.Arrays;
import java.util.List;

public enum CoinTicker {
    BLOCKNET,
    BLOCKNET_TESTNET5,

    BITCOIN,
    BITCOIN_CASH,
    LITECOIN,
    DASHCOIN,
    DIGIBYTE,
    DOGECOIN,
    SYSCOIN,
    PIVX,
    RAVENCOIN,
    UNOBTANIUM,
    PKOIN
    ;

    /**
    * List of supported coins.
    * @return Supported coins
    */
    public static List<CoinTicker> coins() {
        return Arrays.asList(
                BLOCKNET,
                BLOCKNET_TESTNET5,
                BITCOIN,
//            BITCOIN_CASH, - not support on backend
                LITECOIN,
                DASHCOIN,
                DIGIBYTE,
                DOGECOIN,

                SYSCOIN,
                PIVX,
                UNOBTANIUM,
                PKOIN,
                RAVENCOIN
        );
    }
}
