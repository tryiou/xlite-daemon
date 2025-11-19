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
	TREZARCOIN,
	SYSCOIN,
	PIVX,
    ALQOCOIN,
    POLISCOIN,
    PHORECOIN,
    RAVENCOIN,
	BITBAY,
    UNOBTANIUM
    ;

    /**
     * List of supported coins.
     * @return Supported coins
     */
    public static List<CoinTicker> coins() {
        return Arrays.asList(
            BLOCKNET,
            // BLOCKNET_TESTNET5,
            BITCOIN,
            LITECOIN,
            DASHCOIN,
            DOGECOIN,
            SYSCOIN,
            PIVX,
            UNOBTANIUM
            // BITCOIN_CASH, - not support on backend
            // DIGIBYTE, - not support on backend
            // TREZARCOIN, - not support on backend
            // ALQOCOIN, - not support on backend
            // POLISCOIN, - not support on backend
            // PHORECOIN, - not support on backend
            // RAVENCOIN
            // BITBAY - not support on backend
        );
    }
}
