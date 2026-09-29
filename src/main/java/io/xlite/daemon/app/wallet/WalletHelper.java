package io.xlite.daemon.app.wallet;

import com.google.common.base.Preconditions;
import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTicker;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.HasFeeParams;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeer;
import io.xlite.daemon.app.util.AddressBalance;
import io.xlite.daemon.app.util.CloudTransaction;
import io.xlite.daemon.app.util.Sats;
import io.xlite.daemon.app.util.TxTimeField;
import io.xlite.daemon.app.util.TxTimeFieldSigner;
import io.xlite.daemon.app.util.UTXO;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.Wallet;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    /**
     * Build and sign a transaction spending everything not already sent as an
     * output.
     *
     * <p>Only usable for coins whose transactions carry no nTime: resolving
     * nTime needs each input parent's time from chain, and this entry point has
     * no route to it. Use the overload that takes an {@link NTimeResolver} for
     * a time-field coin; calling this one for such a coin logs the refusal and
     * returns null rather than signing against a guessed time.
     */
    public Transaction createRawTransactionWithAllUTXOs(Transaction tx, double amount) {
        if (TxTimeField.appliesTo(coin.getTicker())) {
            LOGGER.warning("[wallet-" + CoinTickerUtils.tickerToString(coin.getTicker())
                    + "] createRawTransactionWithAllUTXOs cannot build a transaction for "
                    + CoinTickerUtils.tickerToString(coin.getTicker())
                    + ": nTime must be signed, and this path cannot resolve it. "
                    + "Use the overload that takes an nTime resolver.");
            return null;
        }
        return createRawTransactionWithAllUTXOs(tx, amount, null);
    }

    /**
     * @param nTimeResolver supplies the nTime to sign for time-field coins;
     *                      must be non-null for those coins, and is ignored
     *                      for the rest
     */
    public Transaction createRawTransactionWithAllUTXOs(Transaction tx, double amount,
            NTimeResolver nTimeResolver) {
        ArrayList<UTXO> utxos = coinSelector(amount);

        if (utxos == null) {
            LOGGER.warning("[wallet-" + coin.getTicker() + "] createRawTransactionWithAllUTXOs: no UTXOs for amount=" + amount);
            return null;
        }

        long nTime = resolveNTime(coin.getTicker(), utxos, nTimeResolver);
        if (nTime == INVALID_NTIME)
            return null;
        Transaction signed = signTransactionWithUtxos(tx, utxos, nTime);
        if (signed == null)
            return null;
        // Mark here rather than inside signing: this path builds and hands the
        // transaction to the service node in one step, so there is no later
        // broadcast callback to do it. Signing itself stays free of wallet
        // mutations, so a caller that only wants to inspect a transaction does
        // not consume its inputs.
        markInputsSpent(utxos);
        return signed;
    }

    /**
     * Sign every selected input.
     *
     * <p>Coins whose transactions carry nTime take a different route. Their
     * signature hash covers nTime, so bitcoinj's {@code addSignedInput} signs a
     * preimage the chain never computes and the node rejects the result at
     * script verification. Those inputs go through
     * {@link TxTimeFieldSigner#addSignedInputs}, which attaches all inputs
     * before signing any of them -- required, because a later input changes
     * the preimage of every signature already made.
     *
     * <p>Time-field inputs are therefore collected first and signed in one
     * call, while standard coins keep signing input by input.
     *
     * @param nTime the nTime to sign, or {@link #NO_NTIME} for a coin whose
     *              transactions carry none
     */
    Transaction signTransactionWithUtxosForTest(Transaction tx, ArrayList<UTXO> selectedUtxos,
            long nTime) {
        return signTransactionWithUtxos(tx, selectedUtxos, nTime);
    }

    /**
     * The signing routine. Package-private so the "signing does not spend"
     * guarantee is testable rather than asserted in a comment: a test that must
     * observe the wallet's state before and after calls this same method
     * production calls, through {@link #signTransactionWithUtxosForTest}.
     */
    private Transaction signTransactionWithUtxos(Transaction tx, ArrayList<UTXO> selectedUtxos,
            long nTime) {
        try {
            if (selectedUtxos == null) {
                LOGGER.warning("[wallet-" + coin.getTicker() + "] signTransactionWithUtxos: no UTXOs provided");
                return null;
            }
            LOGGER.fine("[wallet-" + coin.getTicker() + "] signTransactionWithUtxos: signing " + selectedUtxos.size() + " UTXOs");

            if (nTime != NO_NTIME && (nTime < 0 || nTime > 0xFFFFFFFFL)) {
                LOGGER.warning("[wallet-" + coin.getTicker()
                        + "] signTransactionWithUtxos: nTime out of uint32 range: " + nTime);
                return null;
            }

            if (nTime != NO_NTIME) {
                signTimeFieldInputs(tx, selectedUtxos, nTime);
            } else {
                for (UTXO utxo : selectedUtxos) {
                    if (utxo.isSpent())
                        continue;
                    org.bitcoinj.core.UTXO bUtxo = utxo.createUTXO();
                    AddressBalance addressBalance = requireAddressBalance(utxo);
                    TransactionOutPoint outPoint = new TransactionOutPoint(
                            networkParameters, bUtxo.getIndex(), bUtxo.getHash());
                    tx.addSignedInput(outPoint, bUtxo.getScript(),
                            addressBalance.getPrivateKey().getKey(), Transaction.SigHash.ALL, true);
                    logSignedInput(utxo);
                }
            }
            return tx;
        } catch (Exception e) {
            LOGGER.warning("[wallet-" + coin.getTicker() + "] Error creating transaction" + e.getMessage());
            return null;
        }
    }

    /**
     * Collect the unspent inputs with their keys and scripts, then sign them
     * in a single call so every preimage is computed against the complete
     * input set.
     */
    private void signTimeFieldInputs(Transaction tx, ArrayList<UTXO> selectedUtxos, long nTime) {
        List<ECKey> keys = new ArrayList<>();
        List<TransactionOutPoint> outpoints = new ArrayList<>();
        List<Script> scripts = new ArrayList<>();

        for (UTXO utxo : selectedUtxos) {
            if (utxo.isSpent())
                continue;
            org.bitcoinj.core.UTXO bUtxo = utxo.createUTXO();
            AddressBalance addressBalance = requireAddressBalance(utxo);
            keys.add(addressBalance.getPrivateKey().getKey());
            outpoints.add(new TransactionOutPoint(networkParameters, bUtxo.getIndex(), bUtxo.getHash()));
            scripts.add(bUtxo.getScript());
        }

        TxTimeFieldSigner.addSignedInputs(networkParameters, tx, keys, outpoints, scripts,
                Transaction.SigHash.ALL, nTime);

        for (UTXO utxo : selectedUtxos) {
            if (!utxo.isSpent())
                logSignedInput(utxo);
        }
    }

    /**
     * Mark the just-signed inputs spent and recompute the affected balances.
     *
     * <p>Called by the caller that commits the inputs, never by the signing
     * routine itself. Signing does not know whether the transaction will be
     * broadcast: a dry run builds and signs exactly the same bytes and must
     * leave the funds spendable, so the marking is the caller's decision.
     *
     * <p>{@code sendtransaction} marks through {@link #setAsSpent} after a
     * successful broadcast. The XR service-fee path has no such callback --
     * {@code XRouterFeeUtils} receives its fee transaction already built -- so
     * it marks here; without that, the next XR call would re-select and
     * re-sign the same outputs into a second transaction.
     */
    private void markInputsSpent(ArrayList<UTXO> selectedUtxos) {
        List<AddressBalance> touched = new ArrayList<>();
        for (UTXO utxo : selectedUtxos) {
            if (utxo.isSpent())
                continue;
            utxo.setSpent(true);
            AddressBalance addressBalance = coin.getAddressBalance(utxo.getAddress());
            if (addressBalance != null && !touched.contains(addressBalance))
                touched.add(addressBalance);
        }
        // Once per address that actually held an input, rather than once per
        // input: the balance scan covers every tracked address, so a
        // per-input recompute repeats that scan for inputs that share an
        // address.
        for (AddressBalance addressBalance : touched) {
            addressBalance.calculateBalance();
        }
    }

    private AddressBalance requireAddressBalance(UTXO utxo) {
        AddressBalance addressBalance = coin.getAddressBalance(utxo.getAddress());
        Preconditions.checkNotNull(addressBalance);
        return addressBalance;
    }

    private void logSignedInput(UTXO utxo) {
        LOGGER.fine("[wallet-" + coin.getTicker() + "]   signed input: txid="
                + utxo.getTxid() + " vout=" + utxo.getVout());
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
        BuiltTransaction built = createTransactionSimpleWithFee(coinTicker, address, amount, null, null);
        // buildSimple reports failure by returning null, so unwrap it here rather
        // than letting a caller meet a NullPointerException on .transaction().
        return built == null ? null : built.transaction();
    }

    /**
     * Why {@link #buildSimple} returned no transaction.
     *
     * <p>The builder reports several unrelated failures the same way, and a
     * caller that only sees null has to guess: telling an empty wallet
     * "nTime could not be resolved" would send the reader looking for an
     * upstream problem that was never involved.
     */
    public enum BuildFailure {
        /** The wallet holds nothing spendable. */
        NO_UTXOS,
        /** The selected inputs cannot cover the amount plus fee. */
        INSUFFICIENT_FUNDS,
        /** No combination of inputs covered the amount. */
        SELECTION_FAILED,
        /** Inputs were selected but no nTime could be established for them. */
        NTIME_UNRESOLVED,
        /** The inputs were selected and the nTime resolved, but signing failed. */
        SIGNING_FAILED
    }

    /**
     * A built, signed transaction together with the fee it actually commits to.
     *
     * <p>The fee is carried rather than recomputed by the caller:
     * {@link #fundTransaction} charges for one input more than the finished
     * transaction ends up with (it reserves for a change input while
     * selecting), so a fee derived from the transaction's own input count
     * understates what is really paid. Callers that only want to send can keep
     * using {@link #createTransactionSimple}.
     */
    public record BuiltTransaction(Transaction transaction, long feeSats, long nTime) {
    }

    /**
     * Sentinel for a transaction that carries no nTime field: none is signed
     * and none is spliced into the wire bytes.
     */
    public static final long NO_NTIME = -1L;

    /**
     * The nTime to sign for {@code selected} inputs, or {@link #NO_NTIME} for
     * a coin whose transactions carry none.
     *
     * <p>Fails closed: a time-field coin with no resolver, an out-of-range
     * value, or a resolver that throws yields {@link #INVALID_NTIME} and the
     * caller abandons the transaction. Falling back to a guess here would
     * produce exactly the silently-invalid signature this class exists to
     * prevent.
     */
    static long resolveNTime(CoinTicker coinTicker, List<UTXO> selected,
            NTimeResolver nTimeResolver) {
        if (!TxTimeField.appliesTo(coinTicker))
            return NO_NTIME;
        if (nTimeResolver == null) {
            LOGGER.warning("[wallet-" + CoinTickerUtils.tickerToString(coinTicker)
                    + "] transactions carry nTime but no resolver was supplied; refusing to sign");
            return INVALID_NTIME;
        }
        long nTime;
        try {
            nTime = nTimeResolver.resolve(selected);
        } catch (RuntimeException e) {
            LOGGER.warning("[wallet-" + CoinTickerUtils.tickerToString(coinTicker)
                    + "] could not resolve nTime: " + e.getMessage());
            return INVALID_NTIME;
        }
        if (nTime < 0 || nTime > 0xFFFFFFFFL) {
            LOGGER.warning("[wallet-" + CoinTickerUtils.tickerToString(coinTicker)
                    + "] nTime out of uint32 range: " + nTime);
            return INVALID_NTIME;
        }
        return nTime;
    }

    /** Returned by {@link #resolveNTime} when resolution could not complete. */
    static final long INVALID_NTIME = -2L;

    /**
     * Supplies the nTime to sign for a transaction whose inputs are
     * {@code selected}.
     *
     * <p>Separate from the build because nTime depends on the inputs'
     * parents' times, which live on chain: the caller fetches them. Resolving
     * it here is impossible -- the wallet holds UTXO values, not parent
     * transactions -- and guessing wall-clock time alone is wrong, since a
     * parent may carry a future nTime and the chain rejects a child older
     * than its inputs.
     */
    @FunctionalInterface
    public interface NTimeResolver {
        /**
         * @return the nTime to sign, in {@link #NO_NTIME} to skip nTime
         * @throws RuntimeException if the parent times cannot be fetched; an
         *         unsendable transaction must fail here rather than be
         *         signed against a guess
         */
        long resolve(List<UTXO> selected);
    }

    /**
     * @param nTimeResolver supplies the nTime to sign for time-field coins;
     *                      must be non-null for those coins, and is ignored
     *                      for the rest
     */
    public static BuiltTransaction createTransactionSimpleWithFee(CoinTicker coinTicker, String address,
            double amount, NTimeResolver nTimeResolver) {
        return createTransactionSimpleWithFee(coinTicker, address, amount, nTimeResolver, null);
    }

    /**
     * As {@link #createTransactionSimpleWithFee(CoinTicker, String, double,
     * NTimeResolver)}, additionally reporting why a null result is null.
     *
     * <p>The reason is returned to the caller rather than stashed in a field
     * because sends are serialized per coin, not per process: a static slot
     * would be overwritten by a concurrent send, and the caller would then be
     * told its empty wallet failed to resolve an nTime.
     *
     * @param failureOut receives the reason; may be null when the caller does not
     *                   need it
     */
    public static BuiltTransaction createTransactionSimpleWithFee(CoinTicker coinTicker, String address,
            double amount, NTimeResolver nTimeResolver, BuildFailure[] failureOut) {
        return buildSimple(coinTicker, address, amount, nTimeResolver,
                failureOut == null ? new BuildFailure[1] : failureOut);
    }

    private static BuiltTransaction buildSimple(CoinTicker coinTicker, String address, double amount,
            NTimeResolver nTimeResolver, BuildFailure[] failureOut) {
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        WalletHelper walletHelper = coinInstance.getWalletHelper();
        NetworkParameters params = coinInstance.getNetworkParameters();

        long feePerByte = getFeePerByte(params);
        long minTxFee = getMinTxFee(params);

        // The coin's own BCF COIN factor (base units per whole coin).
        // Never Coin.COIN.value: that hardcodes 8 decimals and misprices
        // every 6-decimal coin 100x in fee, output and dust math below.
        long coinUnit = CoinConfigRegistry.get(CoinTickerUtils.tickerToString(coinTicker)).coinFactor();

        ArrayList<UTXO> allUtxos = walletHelper.sortLeastToGreatest();
        allUtxos.removeIf(UTXO::isSpent);

        if (allUtxos.isEmpty()) {
            LOGGER.warning("[wallet-" + CoinTickerUtils.tickerToString(coinTicker) + "] createTransactionSimple: no UTXOs available");
            failureOut[0] = BuildFailure.NO_UTXOS;
            return null;
        }

        double amountNotIncludingFees = amount;
        double totalAvailable = 0.0;
        for (UTXO utxo : allUtxos) {
            totalAvailable += utxo.getAmount();
        }

        if (totalAvailable < amountNotIncludingFees) {
            LOGGER.warning("[wallet-" + CoinTickerUtils.tickerToString(coinTicker) + "] createTransactionSimple: insufficient funds (have=" + totalAvailable + ", need=" + amountNotIncludingFees + ")");
            failureOut[0] = BuildFailure.INSUFFICIENT_FUNDS;
            return null;
        }

        ArrayList<UTXO> selectedUtxos = new ArrayList<>();
        ArrayList<UTXO> outputs = new ArrayList<>();
        outputs.add(createTransactionOutput(coinTicker, address, amount, params));

        long estimatedFeeSats = Math.max(feePerByte * (192 + 34), minTxFee);
        double estimatedFee = (double) estimatedFeeSats / coinUnit;

        double changeAmt = fundTransaction(allUtxos, selectedUtxos, outputs,
                amountNotIncludingFees, estimatedFee, feePerByte, minTxFee, coinUnit, totalAvailable);
        if (selectedUtxos.isEmpty()) {
            LOGGER.warning("[wallet-" + CoinTickerUtils.tickerToString(coinTicker) + "] createTransactionSimple: failed to select UTXOs");
            failureOut[0] = BuildFailure.SELECTION_FAILED;
            return null;
        }

        Transaction tx = new Transaction(params);
        for (UTXO output : outputs) {
            Address addr = LegacyAddress.fromBase58(params, output.getAddress());
            tx.addOutput(Coin.valueOf(Sats.fromWholeCoins(output.getAmount(), coinUnit)), addr);
        }

        boolean changeAdded = changeAmt > 0 && !isDust(changeAmt, coinUnit, params);
        if (changeAdded) {
            tx.addOutput(Coin.valueOf(Sats.fromWholeCoins(changeAmt, coinUnit)), walletHelper.getChangeAddress());
        }

        // The fee is exactly what leaves the wallet: the selected inputs minus
        // every output actually added. Deriving it from the transaction's own
        // totals rather than from a linear formula keeps it right when change was
        // calculated but dropped as dust, since that value never became an output
        // and must not be subtracted here.
        long feeSats = feeActuallyCharged(selectedUtxos, tx);

        // A time-field chain signs nTime, so it must be fixed before signing
        // and reused verbatim for the wire bytes. Resolve it once, here,
        // against the inputs that were actually selected.
        long nTime = resolveNTime(coinTicker, selectedUtxos, nTimeResolver);
        if (nTime == INVALID_NTIME) {
            failureOut[0] = BuildFailure.NTIME_UNRESOLVED;
            return null;
        }

        Transaction signed = walletHelper.signTransactionWithUtxos(tx, selectedUtxos, nTime);
        if (signed == null) {
            failureOut[0] = BuildFailure.SIGNING_FAILED;
            return null;
        }
        return new BuiltTransaction(signed, feeSats, nTime);
    }


    private static double fundTransaction(ArrayList<UTXO> allUtxos, ArrayList<UTXO> selectedUtxos,
                                          ArrayList<UTXO> recipientOutputs, double sendAmount,
                                          double initialFee,
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
            // Shortfall must fail like the multi-input path — returning
            // change 0.0 here used to build a deterministically invalid
            // transaction (recipient output exceeding the lone input).
            throw new RuntimeException("Not enough funds");
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
            throw new RuntimeException("Not enough funds");
        }

        return totalSelected - totalSendAmount;
    }

    /**
     * The fee a finished transaction actually commits to: the selected input
     * total minus every output it really carries.
     *
     * <p>Both sides are summed in base units. Converting each to whole coins
     * first would round every term separately and could report a figure a few
     * base units away from what the network charges -- and the dry-run
     * response presents this number as the fee paid. Deriving it from the
     * transaction's own outputs rather than from a linear
     * {@code size * feePerByte} formula also stays correct when change was
     * calculated and then dropped as dust, because a dropped value never
     * became an output and so is correctly not subtracted here.
     *
     * <p>Package-private for testing: this is the number the send path
     * reports, and it is the one value a wrong answer about would be
     * invisible to every other check.
     */
    static long feeActuallyCharged(List<UTXO> selectedUtxos, Transaction tx) {
        long inputsSats = 0;
        for (UTXO utxo : selectedUtxos) {
            inputsSats += utxo.getValue();
        }
        long outputsSats = 0;
        for (org.bitcoinj.core.TransactionOutput out : tx.getOutputs()) {
            outputsSats += out.getValue().value;
        }
        return inputsSats - outputsSats;
    }

    private static double calculateFee(int inputCount, int outputCount, long feePerByte, long minTxFee, long coinUnit) {
        long feeSats = Math.max(feePerByte * (192 * inputCount + 34 * outputCount), minTxFee);
        return (double) feeSats / coinUnit;
    }

    private static boolean isDust(double amount, long coinUnit, NetworkParameters params) {
        return Sats.fromWholeCoins(amount, coinUnit) < params.getMinNonDustOutput().value;
    }

    // Package-private for testing. The params must be the coin's own network
    // parameters: a null lookup only resolves BTC/testnet version bytes in
    // bitcoinj's global registry and rejects every altcoin address.
    static UTXO createTransactionOutput(CoinTicker ticker, String address, double amount, NetworkParameters params) {
        // Validates the address against this coin's version bytes; throws
        // AddressFormatException on mismatch. Do not remove: a null-params
        // lookup here rejects every non-BTC address ("No network found").
        LegacyAddress.fromBase58(params, address);
        long coinUnit = CoinConfigRegistry.get(CoinTickerUtils.tickerToString(ticker)).coinFactor();
        Coin coin = Coin.valueOf(Sats.fromWholeCoins(amount, coinUnit));
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
                boolean matched = false;
                for (UTXO utxo : addressBalance.getUtxos()) {
                    if (Sha256Hash.wrap(utxo.getTxid()).equals(txid) && utxo.getVout() == vout) {
                        utxo.setSpent(setSpent);
                        matched = true;
                    }
                }
                // Recalculated only where something actually changed. Recomputing
                // for every address on every input walks that address's whole
                // unspent set, so on a many-address wallet it made marking a
                // transaction proportional to inputs times addresses for no gain:
                // an address with no matching row has the same balance as before.
                if (matched)
                    addressBalance.calculateBalance();
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
