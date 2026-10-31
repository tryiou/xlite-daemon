package io.xlite.daemon.app.testing;

import io.xlite.daemon.app.coinconfig.CoinConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A synthetic coin configuration for tests.
 *
 * <p>Every value a test depends on is named at the call site, because a fixture that quietly
 * mirrors a real coin would make a hardcoded constant agree with itself. Values left unset take the
 * canonical eight-decimal-coin defaults below; that is a convenience, not a claim about any coin.
 */
public final class CoinFixture {

    private final String ticker;
    private final String blockchain;
    private int addressPrefix = 30;
    private int scriptPrefix = 33;
    private int secretPrefix = 158;
    private long coinFactor = 100_000_000L;
    private long feePerByte = 2;
    private long minTxFee = 678;
    private int port = 9332;
    private String dustAmount = "0";
    private int txVersion = 1;
    private boolean timeField = false;

    private CoinFixture(String ticker, String blockchain) {
        this.ticker = ticker;
        this.blockchain = blockchain;
    }

    /** Starts a fixture for one coin. Both names are carried into the configuration verbatim. */
    public static CoinFixture coin(String ticker, String blockchain) {
        return new CoinFixture(ticker, blockchain);
    }

    public CoinFixture prefixes(int address, int script, int secret) {
        this.addressPrefix = address;
        this.scriptPrefix = script;
        this.secretPrefix = secret;
        return this;
    }

    public CoinFixture coinFactor(long coinFactor) {
        this.coinFactor = coinFactor;
        return this;
    }

    public CoinFixture fees(long feePerByte, long minTxFee) {
        this.feePerByte = feePerByte;
        this.minTxFee = minTxFee;
        return this;
    }

    public CoinFixture port(int port) {
        this.port = port;
        return this;
    }

    public CoinFixture dust(String dustAmount) {
        this.dustAmount = dustAmount;
        return this;
    }

    public CoinFixture txVersion(int txVersion) {
        this.txVersion = txVersion;
        return this;
    }

    public CoinFixture timeField(boolean timeField) {
        this.timeField = timeField;
        return this;
    }

    public CoinConfig build() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("AddressPrefix", Integer.toString(addressPrefix));
        entries.put("ScriptPrefix", Integer.toString(scriptPrefix));
        entries.put("SecretPrefix", Integer.toString(secretPrefix));
        entries.put("COIN", Long.toString(coinFactor));
        entries.put("FeePerByte", Long.toString(feePerByte));
        entries.put("MinTxFee", Long.toString(minTxFee));
        entries.put("Port", Integer.toString(port));
        entries.put("DustAmount", dustAmount);
        entries.put("TxVersion", Integer.toString(txVersion));
        entries.put("TxWithTimeField", Boolean.toString(timeField));
        return new CoinConfig(ticker, blockchain, ticker.toLowerCase() + "--test", entries);
    }
}
