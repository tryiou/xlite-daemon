package io.cloudchains.app.coinconfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable view of one coin's configuration as carried by the
 * blockchain-configuration-files repository (manifest entry + the coin's
 * section inside its xbridge conf file).
 *
 * <p>Values are exposed exactly as parsed; interpretation and range checks
 * belong to the parameter layer built on top of this class, not here.</p>
 */
public final class CoinConfig {

    private final String ticker;
    private final String blockchain;
    private final String verId;
    private final Map<String, String> confEntries;

    public CoinConfig(String ticker, String blockchain, String verId, Map<String, String> confEntries) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.blockchain = Objects.requireNonNull(blockchain, "blockchain");
        this.verId = verId == null ? "" : verId;
        this.confEntries = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(confEntries, "confEntries")));
    }

    public String getTicker() {
        return ticker;
    }

    public String getBlockchain() {
        return blockchain;
    }

    public String getVerId() {
        return verId;
    }

    /** Raw key/value map of the coin's xbridge-conf section, as parsed. */
    public Map<String, String> getConfEntries() {
        return confEntries;
    }

    private String required(String key) {
        String v = confEntries.get(key);
        if (v == null || v.trim().isEmpty())
            throw new IllegalStateException("[" + ticker + "] missing required config key '" + key + "'");
        return v.trim();
    }

    private long requiredLong(String key) {
        try {
            return Long.parseLong(required(key));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("[" + ticker + "] config key '" + key
                    + "' is not a number: '" + confEntries.get(key) + "'", e);
        }
    }

    private int requiredInt(String key) {
        try {
            return Math.toIntExact(requiredLong(key));
        } catch (ArithmeticException e) {
            throw new IllegalStateException("[" + ticker + "] config key '" + key
                    + "' exceeds the int range: " + confEntries.get(key), e);
        }
    }

    public int addressPrefix() {
        return requiredInt("AddressPrefix");
    }

    public int scriptPrefix() {
        return requiredInt("ScriptPrefix");
    }

    public int secretPrefix() {
        return requiredInt("SecretPrefix");
    }

    public long coinFactor() {
        return requiredLong("COIN");
    }

    public long feePerByte() {
        return requiredLong("FeePerByte");
    }

    public long minTxFee() {
        return requiredLong("MinTxFee");
    }

    /**
     * Optional in current bcf data: every coin carries DustAmount=0 placeholders.
     * Absent/blank yields null; a present-but-malformed value throws.
     */
    public Long dustAmountOrNull() {
        String v = confEntries.get("DustAmount");
        if (v == null || v.trim().isEmpty())
            return null;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("[" + ticker + "] config key 'DustAmount"
                    + "' is not a number: '" + v + "'", e);
        }
    }

    /** Wallet listen port from the xbridge conf; wallet-conf rpcport may override per user settings. */
    public int port() {
        return requiredInt("Port");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CoinConfig)) return false;
        CoinConfig that = (CoinConfig) o;
        return ticker.equals(that.ticker)
                && blockchain.equals(that.blockchain)
                && verId.equals(that.verId)
                && confEntries.equals(that.confEntries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ticker, blockchain, verId, confEntries);
    }

    @Override
    public String toString() {
        return "CoinConfig{" + ticker + "/" + verId + ", keys=" + confEntries.keySet() + '}';
    }
}
