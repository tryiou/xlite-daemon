package io.cloudchains.app.wallet;

import com.google.common.base.Preconditions;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;
import io.cloudchains.app.util.AddressBalance;
import io.cloudchains.app.util.CloudTransaction;
import io.cloudchains.app.util.UTXO;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.Wallet;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;

public class WalletHelper {
	private CoinInstance coin;
	private NetworkParameters networkParameters;

	public WalletHelper(CoinInstance coinInstance) {
		this.coin = coinInstance;
		this.networkParameters = coin.getNetworkParameters();

	}

	public Transaction createRawTransactionWithAllUTXOs(Transaction tx, double amount) {
		try {
			ArrayList<UTXO> utxos = coinSelector(amount);

			Preconditions.checkNotNull(utxos);
			for (UTXO utxo : utxos) {
				if (utxo.isSpent())
					continue;

				org.bitcoinj.core.UTXO bUtxo = utxo.createUTXO();
				AddressBalance addressBalance = coin.getAddressBalance(utxo.getAddress());
				Preconditions.checkNotNull(addressBalance);

				TransactionOutPoint outPoint = new TransactionOutPoint(networkParameters, bUtxo.getIndex(), bUtxo.getHash());

				tx.addSignedInput(outPoint, bUtxo.getScript(), addressBalance.getPrivateKey().getKey(), Transaction.SigHash.ALL, true);

				utxo.setSpent(true);
				addressBalance.calculateBalance();
			}
			return tx;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public Transaction createRawTransactionWithAllUTXOs(ArrayList<TransactionOutput> outputs, double amount) {
		Transaction tx = new Transaction(networkParameters);

		for (TransactionOutput output : outputs) {
			tx.addOutput(output);
		}

		return createRawTransactionWithAllUTXOs(tx, amount);
	}

	private ArrayList<UTXO> sortLeastToGreatest() {
		ArrayList<UTXO> utxos = new ArrayList<>();

		for (AddressBalance addressBalance : coin.getAddressKeyPairs()) {
			utxos.addAll(addressBalance.getUtxos());
		}

		utxos.sort(Comparator.comparingLong(UTXO::getValue));

		return utxos;
	}

	private ArrayList<UTXO> advancedCoinSorting() {
		ArrayList<UTXO> utxos = new ArrayList<>();

		for (AddressBalance addressBalance : coin.getAddressKeyPairs()) {
			utxos.addAll(addressBalance.getUtxos());
		}

		utxos.sort(Comparator.comparingLong(UTXO::getValue));

		int sizeOfUtxos = utxos.size();

		if (sizeOfUtxos <= 1)
			return utxos;

		ArrayList<UTXO> utxosLowerHalf = new ArrayList<>(utxos.subList(0, ((sizeOfUtxos + 1) / 2)));
		ArrayList<UTXO> utxosGreaterHalf = new ArrayList<>(utxos.subList(((sizeOfUtxos + 1) / 2), sizeOfUtxos));

		ArrayList<UTXO> res = new ArrayList<>();

		while (utxosLowerHalf.size() > 0 || utxosGreaterHalf.size() > 0) {
			if (utxosLowerHalf.size() > 0) {
				res.add(utxosLowerHalf.get(0));
				utxosLowerHalf.remove(0);
			}

			if (utxosGreaterHalf.size() > 0) {
				res.add(utxosGreaterHalf.get(0));
				utxosGreaterHalf.remove(0);
			}
		}

		return res;
	}

	private ArrayList<UTXO> coinSelector(double amount) {
		ArrayList<UTXO> utxos = new ArrayList<>();
		double totalBalance = 0.0;

		for (UTXO utxo : advancedCoinSorting()) {
			if (totalBalance < amount) {
				totalBalance += utxo.getAmount();
				utxos.add(utxo);
			} else {
				break;
			}
		}

		if (utxos.size() > 0)
			return utxos;
		else
			return null;
	}

	public String formatAmount(double amount) {
		DecimalFormat df = new DecimalFormat("#.########");
		return df.format(amount);
	}

	public double getTotalBalance() {
		double totalBalance = 0.0;

		for (AddressBalance addressBalance : coin.getAddressKeyPairs())
			totalBalance += addressBalance.getBalanceProp();

		return totalBalance;
	}

	public double getSpendBalance(double amount) {
		double totalBalance = 0.0;
		ArrayList<UTXO> utxos = coinSelector(amount);

		Preconditions.checkNotNull(utxos);
		for (UTXO utxo : utxos)
			totalBalance += utxo.getAmount();

		return totalBalance;
	}

	public Address getChangeAddress() {
		if (coin.getAddressKeyPairs().isEmpty()) {
			return null;
		}

		return coin.getAddressKeyPairs().get(0).getAddress();
	}

	public AddressBalance generateAddress() {
		Wallet wallet = coin.getWallet();
		NetworkParameters params = coin.getNetworkParameters();

		DeterministicKey key = wallet.freshReceiveKey();
		DumpedPrivateKey privateKey = key.getPrivateKeyEncoded(params);

		Address address = new Address(params, key.getPubKeyHash());

		return new AddressBalance(address, privateKey);
	}

	public AddressBalance generateFromPrivateKey(String privKey) {
		NetworkParameters params = coin.getNetworkParameters();

		ECKey key = DumpedPrivateKey.fromBase58(params, privKey).getKey();
		DumpedPrivateKey privateKey = key.getPrivateKeyEncoded(params);

		Address address = new Address(params, key.getPubKeyHash());

		return new AddressBalance(address, privateKey);
	}

	public void addTransactionToWallet(Transaction transaction) {
		coin.addCloudTransaction(new CloudTransaction(transaction));
	}

	public double getBlocknetFeeAmount(BlocknetPeer blocknetPeer) {
		return blocknetPeer.getxRouterConfiguration().getFeeMap().get("xrSendTransaction");
	}

	public static Transaction createTransactionSimple(CoinTicker coinTicker, String address, double amount) {
		CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
		WalletHelper walletHelper = coinInstance.getWalletHelper();
		NetworkParameters params = coinInstance.getNetworkParameters();

		double fee = coinInstance.getConfigHelper().getFee();
		double totalSpending = amount + fee;
		double totalAvailable = walletHelper.getSpendBalance(totalSpending);
		double changeAmt = (totalAvailable - amount) - fee;

		Address toAddress = Address.fromBase58(params, address);
		Coin sendAmount = Coin.valueOf((long) Math.floor(amount * Coin.COIN.value));
		Coin changeAmount = Coin.valueOf((long) Math.floor(changeAmt * Coin.COIN.value));

		Transaction tx = new Transaction(params);

		if (isP2SHAddress(coinInstance, address)) {
			Script p2shScript = ScriptBuilder.createP2SHOutputScript(toAddress.getHash160());
			tx.addOutput(sendAmount, p2shScript);
		} else {
			tx.addOutput(sendAmount, toAddress);
		}

		if (changeAmount.isPositive())
			tx.addOutput(changeAmount, walletHelper.getChangeAddress());

		return walletHelper.createRawTransactionWithAllUTXOs(tx, totalAvailable);
	}

	public static void setAsSpent(CoinTicker coinTicker, Transaction transaction, boolean setSpent) {
		CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);

		for (TransactionInput input : transaction.getInputs()) {
			Sha256Hash txid = input.getOutpoint().getHash();
			long vout = input.getOutpoint().getIndex();

			if (txid == null)
				continue;

			for (AddressBalance addressBalance : coinInstance.getAddressKeyPairs()) {
				for (UTXO utxo : addressBalance.getUtxos()) {

					if (Sha256Hash.wrap(utxo.getTxid()).equals(txid) && utxo.getVout() == vout) {
						utxo.setSpent(setSpent);
					}
				}
			}
		}
	}

	private static boolean isP2SHAddress(CoinInstance coin, String address) {
		byte[] versionAndDataBytes = Base58.decodeChecked(address);
		int version = versionAndDataBytes[0] & 0xFF;

		if (coin.getNetworkParameters().getAcceptableAddressCodes().length > 2) {
			for (int t : coin.getNetworkParameters().getAcceptableAddressCodes()) {
				if (coin.getNetworkParameters().getAddressHeader() != t && t == version) {
					return true;
				}
			}
		}

		return coin.getNetworkParameters().getP2SHHeader() == version;
	}
}
