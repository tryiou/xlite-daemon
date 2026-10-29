package io.xlite.daemon.app.util;

import com.google.gson.annotations.SerializedName;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

public class UTXO {

    /**
     * A whole-coin amount from a JSON response, in that coin's base units.
     *
     * <p>Every UTXO-ingesting site needs the same two steps -- resolve the
     * coin's divisor, then convert -- and each of them previously did it
     * inline, once per row. Centralising it means the divisor is resolved
     * through one audited path and cannot be a hardcoded 1e8 at any of them.
     *
     * @param wholeCoins the amount as the response states it, in coins
     * @param ticker     the coin
     * @return the amount in base units
     */
    public static long baseUnitsForTicker(CoinTicker ticker, double wholeCoins) {
        long coinFactor = CoinConfigRegistry.get(
                CoinTickerUtils.tickerToString(ticker)).coinFactor();
        return Sats.fromWholeCoins(wholeCoins, coinFactor);
    }
    @SerializedName("address")
    private String addressB58;
    @SerializedName("txhash")
    private String txid;
    @SerializedName("block_number")
    private int height;
    protected transient long value;
    private int vout;
    private volatile boolean spent;
    protected CoinTicker ticker;

    public UTXO(CoinTicker ticker, String addressB58, String txid, int vout, int blockHeight, long value) {
        this.addressB58 = addressB58;
        this.txid = txid;
        this.vout = vout;
        this.height = blockHeight;
        this.value = value;
        this.spent = false;

        this.ticker = ticker;
    }

    public void setSpent(boolean spentBool) {
        spent = spentBool;
    }

    public String getAddress() {
        return addressB58;
    }

    public String getTxid() {
        return txid;
    }

    public long getValue() {
        return value;
    }

    public int getVout() {
        return vout;
    }

    public int getHeight() {
        return height;
    }

    public boolean isSpent() {
        return spent;
    }

    public double getAmount() {
        // Base units to whole coins with this coin's own BCF COIN factor,
        // never a hardcoded universal divisor (6-decimal coins read 100x
        // small otherwise). Fail-fast when the registry is not loaded:
        // an amount without its coin's config cannot be priced.
        long coinFactor = CoinConfigRegistry.get(CoinTickerUtils.tickerToString(ticker)).coinFactor();
        return Sats.toWholeCoins(getValue(), coinFactor);
    }

    public org.bitcoinj.core.UTXO createUTXO() {
        LegacyAddress address = LegacyAddress.fromBase58(CoinInstance.getInstance(this.ticker).getNetworkParameters(), getAddress());

        Script scriptForUTXO = ScriptBuilder.createOutputScript(address);

        Sha256Hash sha256Hash = Sha256Hash.wrap(getTxid());
        return new org.bitcoinj.core.UTXO(sha256Hash, getVout(), Coin.valueOf(getValue()), getHeight(), false, scriptForUTXO, getAddress());
    }

    public String toString() {
        return "\n-------------------------\n" +
                "Address=" + getAddress() +
                "\nTXHash=" + getTxid() +
                "\nVout=" + getVout() +
                "\nBlockNumber=" + getHeight() +
                "\nValue=" + getValue();
    }
}
