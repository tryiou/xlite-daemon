package io.cloudchains.app.wallet;

import com.google.common.base.Preconditions;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.HasFeeParams;
import io.cloudchains.app.net.protocols.blocknet.BlocknetPeer;
import io.cloudchains.app.util.AddressBalance;
import io.cloudchains.app.util.CloudTransaction;
import io.cloudchains.app.util.UTXO;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.wallet.Wallet;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class WalletHelper {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private CoinInstance coin;
    private NetworkParameters networkParameters;

    public WalletHelper(CoinInstance coinInstance) {
        this.coin = coinInstance;
        this.networkParameters = coin.getNetworkParameters();

    }

    public Transaction createRawTransactionWithAllUTXOs(Transaction tx, double amount) {
        ArrayList<UTXO> utxos = coinSelector(amount);

        if (utxos == null) {
            LOGGER.warning("[wallet-" + coin.getTicker() + "] createRawTransactionWithAllUTXOs: no UTXOs for amount=" + amount);
            return null;
        }

        return signTransactionWithUtxos(tx, utxos);
    }

    private Transaction signTransactionWithUtxos(Transaction tx, ArrayList<UTXO> selectedUtxos) {
        try {
            if (selectedUtxos == null) {
                LOGGER.warning("[wallet-" + coin.getTicker() + "] signTransactionWithUtxos: no UTXOs provided");
                return null;
            }
            LOGGER.fine("[wallet-" + coin.getTicker() + "] signTransactionWithUtxos: signing " + selectedUtxos.size() + " UTXOs");
            for (UTXO utxo : selectedUtxos) {
                if (utxo.isSpent())
                    continue;

                org.bitcoinj.core.UTXO bUtxo = utxo.createUTXO();
                AddressBalance addressBalance = coin.getAddressBalance(utxo.getAddress());
                Preconditions.checkNotNull(addressBalance);

                TransactionOutPoint outPoint = new TransactionOutPoint(networkParameters, bUtxo.getIndex(), bUtxo.getHash());

                tx.addSignedInput(outPoint, bUtxo.getScript(), addressBalance.getPrivateKey().getKey(), Transaction.SigHash.ALL, true);
                LOGGER.fine("[wallet-" + coin.getTicker() + "]   signed input: txid=" + utxo.getTxid() + " vout=" + utxo.getVout());

                utxo.setSpent(true);
                addressBalance.calculateBalance();
            }
            return tx;
        } catch (Exception e) {
            LOGGER.warning("[wallet-" + coin.getTicker() + "] Error creating transaction" + e.getMessage());
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
        LOGGER.fine("[wallet-" + coin.getTicker() + "] advancedCoinSorting: " + coin.getAddressKeyPairs().size() + " addresses tracked locally");
        for (AddressBalance addressBalance : coin.getAddressKeyPairs()) {
            LOGGER.fine("[wallet-" + coin.getTicker() + "]   addr=" + addressBalance.getAddress().toBase58() + " utxos=" + addressBalance.getUtxos().size());
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

        ArrayList<UTXO> sorted = advancedCoinSorting();
        LOGGER.fine("[wallet-" + coin.getTicker() + "] coinSelector: requested=" + amount + ", available UTXOs=" + sorted.size());
        for (UTXO utxo : sorted) {
            LOGGER.fine("[wallet-" + coin.getTicker() + "]   UTXO: txid=" + utxo.getTxid() + " vout=" + utxo.getVout() + " amount=" + utxo.getAmount() + " spent=" + utxo.isSpent());
        }

        for (UTXO utxo : sorted) {
            if (totalBalance < amount) {
                totalBalance += utxo.getAmount();
                utxos.add(utxo);
            } else {
                break;
            }
        }

        if (utxos.size() > 0) {
            LOGGER.fine("[wallet-" + coin.getTicker() + "] coinSelector: selected " + utxos.size() + " UTXOs, total=" + totalBalance);
            return utxos;
        } else {
            LOGGER.warning("[wallet-" + coin.getTicker() + "] coinSelector: no UTXOs found (requested=" + amount + ", available in wallet=" + sorted.size() + ")");
            return null;
        }
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

        if (utxos == null) {
            LOGGER.warning("[wallet-" + coin.getTicker() + "] getSpendBalance: insufficient funds (requested=" + amount + ")");
            return 0.0;
        }
        for (UTXO utxo : utxos)
            totalBalance += utxo.getAmount();

        LOGGER.fine("[wallet-" + coin.getTicker() + "] getSpendBalance: available=" + totalBalance + " for request=" + amount);
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

        LegacyAddress address = LegacyAddress.fromPubKeyHash(params, key.getPubKeyHash());

        return new AddressBalance(address, privateKey);
    }

    public AddressBalance generateFromPrivateKey(String privKey) {
        NetworkParameters params = coin.getNetworkParameters();

        ECKey key = DumpedPrivateKey.fromBase58(params, privKey).getKey();
        DumpedPrivateKey privateKey = key.getPrivateKeyEncoded(params);

        LegacyAddress address = LegacyAddress.fromPubKeyHash(params, key.getPubKeyHash());

        return new AddressBalance(address, privateKey);
    }

    public void addTransactionToWallet(Transaction transaction) {
        coin.addCloudTransaction(new CloudTransaction(transaction));
    }

    public double getBlocknetFeeAmount(BlocknetPeer blocknetPeer) {
        return blocknetPeer.getxRouterConfiguration().getFeeMap().get("xrSendTransaction");
    }

    public static Transaction createTransactionSimple(CoinTicker coinTicker, String address, double amount) {
        return createTransactionSimple(coinTicker, address, amount, false);
    }

    public static Transaction createTransactionSimple(CoinTicker coinTicker, String address, double amount, boolean subtractFees) {
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        WalletHelper walletHelper = coinInstance.getWalletHelper();
        NetworkParameters params = coinInstance.getNetworkParameters();

        long feePerByte = getFeePerByte(params);
        long minTxFee = getMinTxFee(params);

        long coinUnit = Coin.COIN.value;

        ArrayList<UTXO> allUtxos = walletHelper.sortLeastToGreatest();
        allUtxos.removeIf(UTXO::isSpent);

        if (allUtxos.isEmpty()) {
            LOGGER.warning("[wallet-" + CoinTickerUtils.tickerToString(coinTicker) + "] createTransactionSimple: no UTXOs available");
            return null;
        }

        double amountNotIncludingFees = amount;
        double totalAvailable = 0.0;
        for (UTXO utxo : allUtxos) {
            totalAvailable += utxo.getAmount();
        }

        if (totalAvailable < amountNotIncludingFees) {
            LOGGER.warning("[wallet-" + CoinTickerUtils.tickerToString(coinTicker) + "] createTransactionSimple: insufficient funds (have=" + totalAvailable + ", need=" + amountNotIncludingFees + ")");
            return null;
        }

        ArrayList<UTXO> selectedUtxos = new ArrayList<>();
        ArrayList<UTXO> outputs = new ArrayList<>();
        outputs.add(createTransactionOutput(coinTicker, address, amount));

        long estimatedFeeSats = Math.max(feePerByte * (192 + 34), minTxFee);
        double estimatedFee = (double) estimatedFeeSats / coinUnit;

        double changeAmt = fundTransaction(allUtxos, selectedUtxos, outputs, amountNotIncludingFees, estimatedFee, subtractFees, feePerByte, minTxFee, coinUnit, totalAvailable);

        if (selectedUtxos.isEmpty()) {
            LOGGER.warning("[wallet-" + CoinTickerUtils.tickerToString(coinTicker) + "] createTransactionSimple: failed to select UTXOs");
            return null;
        }

        Transaction tx = new Transaction(params);
        for (UTXO output : outputs) {
            Address addr = LegacyAddress.fromBase58(params, output.getAddress());
            tx.addOutput(Coin.valueOf((long) (output.getAmount() * coinUnit)), addr);
        }

        if (changeAmt > 0 && !isDust(changeAmt, params)) {
            tx.addOutput(Coin.valueOf((long) (changeAmt * coinUnit)), walletHelper.getChangeAddress());
        }

        return walletHelper.signTransactionWithUtxos(tx, selectedUtxos);
    }


    private static double fundTransaction(ArrayList<UTXO> allUtxos, ArrayList<UTXO> selectedUtxos,
                                          ArrayList<UTXO> recipientOutputs, double sendAmount,
                                          double initialFee, boolean subtractFees,
                                          long feePerByte, long minTxFee, long coinUnit, double totalAvailable) {
        double totalSelected = 0.0;
        double fees = initialFee;
        double changeAmount = 0.0;

        allUtxos.sort(Comparator.comparingLong(UTXO::getValue));

        if (allUtxos.size() == 1) {
            UTXO utxo = allUtxos.get(0);
            selectedUtxos.add(utxo);
            totalSelected = utxo.getAmount();
            double required = sendAmount + fees;
            if (totalSelected >= required) {
                return totalSelected - required;
            }
            return 0.0;
        }

        UTXO largestUtxo = allUtxos.get(allUtxos.size() - 1);
        changeAmount = largestUtxo.getAmount();

        for (int i = allUtxos.size() - 1; i >= 0; i--) {
            fees = calculateFee(selectedUtxos.size() + 1, recipientOutputs.size() + 1, feePerByte, minTxFee, coinUnit);
            double requiredAmount = sendAmount + fees;
            UTXO utxo = allUtxos.get(i);

            if (largestUtxo.getAmount() < requiredAmount) {
                if (totalSelected < requiredAmount) {
                    if (totalSelected == 0.0) {
                        changeAmount = utxo.getAmount();
                    }
                    totalSelected += utxo.getAmount();
                    selectedUtxos.add(utxo);
                    continue;
                } else {
                    break;
                }
            } else {
                if (i == 0 || utxo.getAmount() < requiredAmount) {
                    UTXO prevUtxo = allUtxos.get(i + 1);
                    if (totalSelected == 0.0) {
                        changeAmount = prevUtxo.getAmount();
                    }
                    totalSelected += prevUtxo.getAmount();
                    selectedUtxos.add(prevUtxo);
                    break;
                }
            }
        }

        double totalSendAmount = sendAmount + fees;
        if (totalSelected < totalSendAmount) {
            if (subtractFees) {
                return 0.0;
            }
            throw new RuntimeException("Not enough funds");
        }

        return totalSelected - totalSendAmount;
    }

    private static double calculateFee(int inputCount, int outputCount, long feePerByte, long minTxFee, long coinUnit) {
        long feeSats = Math.max(feePerByte * (192 * inputCount + 34 * outputCount), minTxFee);
        return (double) feeSats / coinUnit;
    }

    private static boolean isDust(double amount, NetworkParameters params) {
        return amount * Coin.COIN.value < params.getMinNonDustOutput().value;
    }

    private static UTXO createTransactionOutput(CoinTicker ticker, String address, double amount) {
        LegacyAddress addr = LegacyAddress.fromBase58(null, address);
        Coin coin = Coin.valueOf((long) (amount * Coin.COIN.value));
        return new UTXO(ticker, address, "", 0, 0, coin.value);
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
        return coin.getNetworkParameters().getP2SHHeader() == version;
    }

    public static long getFeePerByte(NetworkParameters params) {
        if (params instanceof HasFeeParams)
            return ((HasFeeParams) params).getFeePerByte();
        throw new RuntimeException("Failed to get feePerByte for " + params.getClass().getSimpleName());
    }

    public static long getMinTxFee(NetworkParameters params) {
        if (params instanceof HasFeeParams)
            return ((HasFeeParams) params).getMinTxFee();
        throw new RuntimeException("Failed to get minTxFee for " + params.getClass().getSimpleName());
    }
}
