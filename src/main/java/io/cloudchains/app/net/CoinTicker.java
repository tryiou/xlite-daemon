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
	BITBAY
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
            BITCOIN_CASH,
            LITECOIN,
            DASHCOIN,
            DIGIBYTE,
            DOGECOIN,
            TREZARCOIN,
            SYSCOIN,
            PIVX,
            ALQOCOIN,
            // TODO Polis and Phore are disabled until supported on backend
//            POLISCOIN,
//            PHORECOIN,
            RAVENCOIN,
            BITBAY
        );
    }
}
