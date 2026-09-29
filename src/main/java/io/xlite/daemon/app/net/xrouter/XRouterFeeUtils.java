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
import org.bitcoinj.core.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class XRouterFeeUtils {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    public static String getXRouterFeeTx(BlocknetPeer blocknetPeer, String xRouterCommand) {
        CoinInstance blocknetCoin = CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork());
        WalletHelper blocknetWalletHelper = blocknetCoin.getWalletHelper();
        NetworkParameters params = blocknetCoin.getNetworkParameters();
        XRouterConfiguration xRouterConfig = blocknetPeer.getxRouterConfiguration();

        Preconditions.checkNotNull(xRouterConfig, "XRouter config was not received yet!");

        HashMap<String, Double> feeMap = xRouterConfig.getFeeMap();

        if (!feeMap.containsKey(xRouterCommand)) {
            LOGGER.finer("[xrouter-fee-utils] WARNING: Invalid/unknown XRouter command supplied to getXRouterFeeTx()! Assuming this command is free.");
            LOGGER.finer("[xrouter-fee-utils] Command: " + xRouterCommand);

            return "nohash;nofee";
        }

        double fee = feeMap.get(xRouterCommand);

        // All XRouter fees settle in the active Blocknet network coin:
        // convert with its own BCF COIN factor, never a hardcoded constant.
        long feeCoinUnit = CoinConfigRegistry.get(
                CoinTickerUtils.tickerToString(blocknetCoin.getTicker())).coinFactor();
        Coin xRouterFeeAmt = Coin.valueOf(Sats.fromWholeCoins(fee, feeCoinUnit));
        if (xRouterFeeAmt.value == 0) {
            LOGGER.finer("[xrouter-fee-utils] DEBUG: This command is free.");
            return "nohash;nofee";
        }

        long feePerByte = WalletHelper.getFeePerByte(blocknetCoin.getNetworkParameters());
        long minTxFee = WalletHelper.getMinTxFee(blocknetCoin.getNetworkParameters());
        long networkFeeSats = Math.max(feePerByte * (192 + 34), minTxFee);
        double networkFee = Sats.toWholeCoins(networkFeeSats, feeCoinUnit);

        double totalSpending = fee + networkFee;
        double totalAvailable = blocknetWalletHelper.getSpendBalance(totalSpending);
        double changeAmt = totalAvailable - networkFee - fee;

        LegacyAddress xRouterPaymentAddress = LegacyAddress.fromBase58(params, xRouterConfig.getFeeAddress());
        Coin blocknetNetworkFeeAmt = Coin.valueOf(networkFeeSats);
        Coin xRouterChangeAmt = Coin.valueOf(Sats.fromWholeCoins(totalAvailable, feeCoinUnit)).minus(blocknetNetworkFeeAmt).minus(xRouterFeeAmt);

        TransactionOutput feeOutput = new TransactionOutput(params, null, xRouterFeeAmt, xRouterPaymentAddress);

        ArrayList<TransactionOutput> outputs = new ArrayList<>();
        outputs.add(feeOutput);

        if (changeAmt > 0.06) {
            double halvedAmt = changeAmt / 3;
            Coin halvedChangeAmt = Coin.valueOf(Sats.fromWholeCoins(halvedAmt, feeCoinUnit));
            TransactionOutput halvedChangeOutput = new TransactionOutput(params, null, halvedChangeAmt, blocknetWalletHelper.getChangeAddress());

            for (int i = 0; i < 3; i++) {
                outputs.add(halvedChangeOutput);
            }
        } else {
            TransactionOutput changeOutput = new TransactionOutput(params, null, xRouterChangeAmt, blocknetWalletHelper.getChangeAddress());
            outputs.add(changeOutput);
        }

        Transaction xRouterFeeTx = blocknetWalletHelper.createRawTransactionWithAllUTXOs(outputs, totalAvailable);
        if (xRouterFeeTx == null) {
            // The builder reports failure by returning null, and it does so for a
            // coin whose transactions need an nTime this path cannot supply. Left
            // unchecked that is a NullPointerException here; naming it keeps the
            // cause visible to whoever is wiring XRouter fees up.
            throw new IllegalStateException(
                    "cannot build the XRouter fee transaction: the builder returned no "
                            + "transaction, meaning it could not select inputs to cover the fee "
                            + "or could not sign them. The reason is logged as a warning above.");
        }

        String feetx = new String(Hex.encode(xRouterFeeTx.bitcoinSerialize()));
        LOGGER.finer("[xrouter-fee-utils] XRouter fee transaction string representation:");
        LOGGER.finer(xRouterFeeTx.toString());
        LOGGER.finer("[xrouter-fee-utils] DEBUG: Feetx: " + feetx);
        return feetx;
    }

    public static TransactionOutput createXrSendTransactionFeeOutput(BlocknetPeer blocknetPeer) {
        CoinInstance blocknetCoin = CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork());
        XRouterConfiguration xRouterConfig = blocknetPeer.getxRouterConfiguration();
        HashMap<String, Double> feeMap = xRouterConfig.getFeeMap();
        Preconditions.checkState(feeMap.containsKey("xrSendTransaction"), "Fee map has no fee for xrSendTransaction");

        double fee = feeMap.get("xrSendTransaction");
        String feeAddress = xRouterConfig.getFeeAddress();

        long sendFeeCoinUnit = CoinConfigRegistry.get(
                CoinTickerUtils.tickerToString(blocknetCoin.getTicker())).coinFactor();
        Coin feeAmount = Coin.valueOf(Sats.fromWholeCoins(fee, sendFeeCoinUnit));

        return new TransactionOutput(blocknetCoin.getNetworkParameters(), null, feeAmount, LegacyAddress.fromBase58(blocknetCoin.getNetworkParameters(), feeAddress));
    }

    public static String coveredXrFee(BlocknetPeer blocknetPeer, Transaction transaction) {
        CoinInstance blocknetCoin = CoinInstance.getInstance(CoinInstance.getActiveBlocknetNetwork());
        WalletHelper.setAsSpent(blocknetCoin.getTicker(), transaction, true);

        String xrFee = getXRouterFeeTx(blocknetPeer, "xrSendTransaction");

        if (xrFee.equals("nohash;nofee"))
            return xrFee;

        Transaction tx = new Transaction(blocknetCoin.getNetworkParameters(), Hex.decode(xrFee));

        if (tx.getInputs().size() == 0)
            return null;

        return xrFee;
    }
}
