package io.xlite.daemon.app.coinconfig;

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
     * Whether the coin's wire format carries an nTime field after nVersion
     * (Peercoin-derived chains set TxWithTimeField=true in BCF). Opt-in:
     * absent/blank yields false; a present-but-unparseable value throws.
     */
    public boolean txWithTimeField() {
        String v = confEntries.get("TxWithTimeField");
        if (v == null || v.trim().isEmpty())
            return false;
        if (v.trim().equalsIgnoreCase("true"))
            return true;
        if (v.trim().equalsIgnoreCase("false"))
            return false;
        throw new IllegalStateException("[" + ticker + "] config key 'TxWithTimeField"
                + "' is not true/false: '" + v + "'");
    }

    /**
     * The coin's dust floor in base units, from BCF. Required; 0 means the
     * coin's daemon has no dust gate (Firo), not "unspecified".
     */
    public long dustAmount() {
        return requiredLong("DustAmount");
    }

    /** Wallet listen port from the xbridge conf; wallet-conf rpcport may override per user settings. */
    public int port() {
        return requiredInt("Port");
    }

    /**
     * DTO map for RPC exposure — 11-field (identity + prefixes/fees/port/dust).
     * dustAmount passes through verbatim: 0 is a real value, not an absence.
     */
    public Map<String, Object> toDtoMap() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("ticker", ticker);
        m.put("blockchain", blockchain);
        m.put("verId", verId);
        m.put("addressPrefix", addressPrefix());
        m.put("scriptPrefix", scriptPrefix());
        m.put("secretPrefix", secretPrefix());
        m.put("coin", coinFactor());
        m.put("feePerByte", feePerByte());
        m.put("minTxFee", minTxFee());
        m.put("port", port());
        m.put("dustAmount", dustAmount());
        return Collections.unmodifiableMap(m);
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
