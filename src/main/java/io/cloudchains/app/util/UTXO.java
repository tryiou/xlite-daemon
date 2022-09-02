package io.cloudchains.app.util;

import com.google.gson.annotations.SerializedName;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

public class UTXO {
	@SerializedName("address") private String addressB58;
	@SerializedName("txhash") private String txid;
	@SerializedName("block_number") private int height;
	protected transient long value;
	private int vout;
	private boolean spent;
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
		return getValue() / 100000000.0;
	}

	public org.bitcoinj.core.UTXO createUTXO() {
		Address address = Address.fromBase58(CoinInstance.getInstance(this.ticker).getNetworkParameters(), getAddress());

		Script scriptForUTXO = ScriptBuilder.createOutputScript(address);

		Sha256Hash sha256Hash = Sha256Hash.wrap(getTxid());
		return new org.bitcoinj.core.UTXO(sha256Hash, getVout(), Coin.valueOf(getValue()), getHeight(), true, scriptForUTXO, getAddress());
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
