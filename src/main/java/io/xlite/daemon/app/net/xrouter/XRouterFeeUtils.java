package io.xlite.daemon.app.net.xrouter;

import com.google.common.base.Preconditions;
import com.subgraph.orchid.encoders.Hex;

import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.net.protocols.blocknet.BlocknetPeer;
import io.xlite.daemon.app.util.Sats;
import io.xlite.daemon.app.util.XRouterConfiguration;
import io.xlite.daemon.app.wallet.WalletHelper;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public final class XRouterFeeUtils {
    private XRouterFeeUtils() {}

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    /** Change above this many whole coins is split into three outputs. */
    private static final double CHANGE_SPLIT_THRESHOLD = 0.06;

    public static String getXRouterFeeTx(BlocknetPeer blocknetPeer, String xRouterCommand) {
        CoinInstance blocknetCoin =
                CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork());
        WalletHelper blocknetWalletHelper = blocknetCoin.getWalletHelper();
        NetworkParameters params = blocknetCoin.getNetworkParameters();
        XRouterConfiguration xRouterConfig = blocknetPeer.getxRouterConfiguration();

        Preconditions.checkNotNull(xRouterConfig, "XRouter config was not received yet!");

        Map<String, Double> feeMap = xRouterConfig.getFeeMap();
        if (!feeMap.containsKey(xRouterCommand)) {
            LOGGER.finer(
                    "[xrouter-fee-utils] WARNING: Invalid/unknown XRouter command supplied to"
                            + " getXRouterFeeTx()! Assuming this command is free.");
            LOGGER.finer("[xrouter-fee-utils] Command: " + xRouterCommand);
            return "nohash;nofee";
        }

        // All XRouter fees settle in the active Blocknet network coin: convert with its own
        // BCF COIN factor, never a hardcoded constant.
        long feeCoinUnit =
                CoinConfigRegistry.get(CoinTickerUtils.tickerToString(blocknetCoin.getTicker()))
                        .coinFactor();
        double fee = feeMap.get(xRouterCommand);
        Coin xRouterFeeAmt = Coin.valueOf(Sats.fromWholeCoins(fee, feeCoinUnit));
        if (xRouterFeeAmt.value == 0) {
            LOGGER.finer("[xrouter-fee-utils] DEBUG: This command is free.");
            return "nohash;nofee";
        }

        long networkFeeSats =
                Math.max(
                        WalletHelper.getFeePerByte(blocknetCoin.getNetworkParameters())
                                * (192 + 34),
                        WalletHelper.getMinTxFee(blocknetCoin.getNetworkParameters()));
        double networkFee = Sats.toWholeCoins(networkFeeSats, feeCoinUnit);
        double totalAvailable = blocknetWalletHelper.getSpendBalance(fee + networkFee);

        FeeContext context =
                new FeeContext(
                        params,
                        blocknetWalletHelper,
                        xRouterConfig,
                        feeCoinUnit,
                        xRouterFeeAmt,
                        networkFeeSats,
                        totalAvailable - networkFee - fee,
                        totalAvailable);
        Transaction xRouterFeeTx = buildFeeTransaction(context);

        String feetx =
                new String(Hex.encode(xRouterFeeTx.bitcoinSerialize()), StandardCharsets.UTF_8);
        LOGGER.finer("[xrouter-fee-utils] XRouter fee transaction string representation:");
        LOGGER.finer(xRouterFeeTx.toString());
        LOGGER.finer("[xrouter-fee-utils] DEBUG: Feetx: " + feetx);
        return feetx;
    }

    /** Everything the fee transaction is assembled from. */
    private record FeeContext(
            NetworkParameters params,
            WalletHelper wallet,
            XRouterConfiguration config,
            long coinUnit,
            Coin feeAmount,
            long networkFeeSats,
            double changeAmount,
            double totalAvailable) {}

    private static Transaction buildFeeTransaction(FeeContext context) {
        LegacyAddress xRouterPaymentAddress =
                LegacyAddress.fromBase58(context.params(), context.config().getFeeAddress());

        ArrayList<TransactionOutput> outputs = new ArrayList<>();
        outputs.add(
                new TransactionOutput(
                        context.params(), null, context.feeAmount(), xRouterPaymentAddress));
        addChangeOutputs(outputs, context);

        Transaction xRouterFeeTx =
                context.wallet()
                        .createRawTransactionWithAllUTXOs(outputs, context.totalAvailable());
        if (xRouterFeeTx == null) {
            // The builder reports failure by returning null, and it does so for a coin whose
            // transactions need an nTime this path cannot supply. Left unchecked that is a
            // NullPointerException here; naming it keeps the cause visible to whoever is wiring
            // XRouter fees up.
            throw new IllegalStateException(
                    "cannot build the XRouter fee transaction: the builder returned no "
                            + "transaction, meaning it could not select inputs to cover the fee "
                            + "or could not sign them. The reason is logged as a warning above.");
        }
        return xRouterFeeTx;
    }

    private static void addChangeOutputs(ArrayList<TransactionOutput> outputs, FeeContext context) {
        if (context.changeAmount() > CHANGE_SPLIT_THRESHOLD) {
            Coin third =
                    Coin.valueOf(
                            Sats.fromWholeCoins(context.changeAmount() / 3, context.coinUnit()));
            TransactionOutput thirdOutput =
                    new TransactionOutput(
                            context.params(), null, third, context.wallet().getChangeAddress());
            for (int i = 0; i < 3; i++) {
                outputs.add(thirdOutput);
            }
            return;
        }
        Coin change =
                Coin.valueOf(Sats.fromWholeCoins(context.totalAvailable(), context.coinUnit()))
                        .minus(Coin.valueOf(context.networkFeeSats()))
                        .minus(context.feeAmount());
        outputs.add(
                new TransactionOutput(
                        context.params(), null, change, context.wallet().getChangeAddress()));
    }

    public static TransactionOutput createXrSendTransactionFeeOutput(BlocknetPeer blocknetPeer) {
        CoinInstance blocknetCoin =
                CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork());
        XRouterConfiguration xRouterConfig = blocknetPeer.getxRouterConfiguration();
        Map<String, Double> feeMap = xRouterConfig.getFeeMap();
        Preconditions.checkState(
                feeMap.containsKey("xrSendTransaction"),
                "Fee map has no fee for xrSendTransaction");

        double fee = feeMap.get("xrSendTransaction");
        String feeAddress = xRouterConfig.getFeeAddress();

        long sendFeeCoinUnit =
                CoinConfigRegistry.get(CoinTickerUtils.tickerToString(blocknetCoin.getTicker()))
                        .coinFactor();
        Coin feeAmount = Coin.valueOf(Sats.fromWholeCoins(fee, sendFeeCoinUnit));

        return new TransactionOutput(
                blocknetCoin.getNetworkParameters(),
                null,
                feeAmount,
                LegacyAddress.fromBase58(blocknetCoin.getNetworkParameters(), feeAddress));
    }

    public static String coveredXrFee(BlocknetPeer blocknetPeer, Transaction transaction) {
        CoinInstance blocknetCoin =
                CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork());
        WalletHelper.setAsSpent(blocknetCoin.getTicker(), transaction, true);

        String xrFee = getXRouterFeeTx(blocknetPeer, "xrSendTransaction");

        if (xrFee.equals("nohash;nofee")) {
            return xrFee;
        }

        Transaction tx = new Transaction(blocknetCoin.getNetworkParameters(), Hex.decode(xrFee));

        if (tx.getInputs().size() == 0) {
            return null;
        }

        return xrFee;
    }
}
