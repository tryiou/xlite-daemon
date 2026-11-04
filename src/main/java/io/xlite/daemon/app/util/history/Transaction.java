package io.xlite.daemon.app.util.history;

import com.google.gson.annotations.SerializedName;

public class Transaction {
    @SerializedName("address")
    private String addressB58;

    @SerializedName("txhash")
    private String txid;

    @SerializedName("blockhash")
    private String blockhash;

    @SerializedName("category")
    private String category;

    private double fee;
    protected transient double value;
    private int vout;
    private int confirmations;
    private int blocktime;
    private int blockheight;
    private int time;

    public Transaction(
            String addressB58,
            String txid,
            String blockhash,
            int vout,
            double value,
            int confirmations,
            int blocktime) {
        this.addressB58 = addressB58;
        this.txid = txid;
        this.blockhash = blockhash;
        this.vout = vout;
        this.value = value;

        this.confirmations = confirmations;
        this.blocktime = blocktime;
        this.time = blocktime;
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

    public void setValue(double value) {
        this.value = value;
    }

    public int getVout() {
        return vout;
    }

    public int getBlocktime() {
        return blocktime;
    }

    /** Height the transaction was mined at; 0 while it is still in the mempool. */
    public int getBlockheight() {
        return blockheight;
    }

    public void setBlockheight(int blockheight) {
        this.blockheight = blockheight;
    }

    /**
     * Sort/display time. Confirmed rows use their block time; a mempool row uses the transaction's
     * own timestamp and never 0, because the GUI indexes history by this field.
     */
    public int getTime() {
        return time;
    }

    public void setTime(int time) {
        this.time = time;
    }

    public int getConfirmations() {
        return confirmations;
    }

    public String getCategory() {
        return category;
    }

    @Override
    public String toString() {
        return "\n-------------------------\n"
                + "Address="
                + getAddress()
                + "\nTXHash="
                + getTxid()
                + "\nVout="
                + getVout()
                + "\nValue="
                + getValue();
    }
}
