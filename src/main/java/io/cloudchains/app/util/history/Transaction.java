package io.cloudchains.app.util.history;

import com.google.gson.annotations.SerializedName;
import io.cloudchains.app.net.CoinTicker;

import java.util.List;

public class Transaction {
    @SerializedName("address") private String addressB58;
    @SerializedName("txhash") private String txid;
    @SerializedName("blockhash") private String blockhash;
    @SerializedName("category") private String category;
    private double fee;
    protected transient double value;
    private int vout;
    private int confirmations;
    private int blocktime;
    protected transient List<String> from_addresses;
    protected CoinTicker ticker;

    public Transaction(CoinTicker ticker, String addressB58, String txid, String blockhash, int vout, double value, int confirmations, int blocktime, List<String> from_addresses) {
        this.addressB58 = addressB58;
        this.txid = txid;
        this.blockhash = blockhash;
        this.vout = vout;
        this.value = value;

        this.confirmations = confirmations;
        this.blocktime = blocktime;
        this.from_addresses = from_addresses;

        this.ticker = ticker;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setFee(double fee) {
        this.fee = fee;
    }
    public double getFee() {
        return fee;
    }

    public String uid() {
        return txid + ":" + vout + ":" + category;
    }

    public String getAddress() {
        return addressB58;
    }

    public String getTxid() {
        return txid;
    }

    public String getBlockhash() {
        return blockhash;
    }

    public double getValue() {
        return value;
    }

    public int getVout() {
        return vout;
    }

    public int getBlocktime() {
        return blocktime;
    }

    public int getConfirmations() {
        return confirmations;
    }

    public List<String> getFrom_addresses() {
        return from_addresses;
    }

    public String getCategory() {
        return category;
    }

    public String toString() {
        return "\n-------------------------\n" +
                "Address=" + getAddress() +
                "\nTXHash=" + getTxid() +
                "\nVout=" + getVout() +
                "\nValue=" + getValue();
    }
}
