package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonObject;

import io.xlite.daemon.app.coinconfig.CoinConfigRegistry;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.util.Sats;
import io.xlite.daemon.app.wallet.WalletHelper;

import org.bitcoinj.core.NetworkParameters;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Network and chain-state methods that do not read transaction data: they describe the node a
 * wallet talks to.
 */
final class NetworkMethods {

    private final CoinInstance coin;

    NetworkMethods(CoinInstance coin) {
        this.coin = coin;
    }

    Map<String, RpcMethod> methods() {
        return Map.of(
                "getinfo", params -> getInfo(),
                "getblockcount", params -> blockCount(),
                "getnetworkinfo", params -> getNetworkInfo(),
                "getfees", params -> fees(),
                "getblockchaininfo", params -> blockchainInfo());
    }

    private JsonObject getInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("protocolversion", protocolVersion());
        info.addProperty("ticker", CoinTickerUtils.tickerToString(coin.getTicker()));
        info.addProperty("balance", coin.getAllBalances());
        info.addProperty("testnet", coin.isTestnet());
        info.addProperty("difficulty", 0.0);
        // Static because the peer-to-peer path is being retired: chain data comes from a provider.
        info.addProperty("connections", 1);
        info.addProperty("blocks", chainHeight());
        info.addProperty("keypoolsize", coin.getAddressKeyPairs().size());
        info.addProperty("keypoololdest", 0.0);
        info.addProperty("relayfee", relayFee());
        info.addProperty("networkactive", true);
        info.addProperty("timeoffset", 0);
        info.addProperty("rpcready", coin.isInstanceRunning());
        return RpcResponse.ok(info);
    }

    private JsonObject blockCount() {
        return RpcResponse.ok(chainHeight());
    }

    /**
     * The provider's tip, or 0 while the coin has no session.
     *
     * <p>The cache's negative sentinel means "never polled"; a Bitcoin-RPC client reads {@code
     * blocks} as a count, so an unsynchronised coin reports 0 rather than -1. The readiness flag is
     * {@code rpcready}, not this number.
     */
    private int chainHeight() {
        return Math.max(0, CoinInstance.getBlockCountByTicker(coin.getTicker()));
    }

    private JsonObject getNetworkInfo() {
        JsonObject networkInfo = new JsonObject();
        networkInfo.addProperty("protocolversion", protocolVersion());
        networkInfo.addProperty("ticker", CoinTickerUtils.tickerToString(coin.getTicker()));
        networkInfo.addProperty("subversion", CoinInstance.getVersionString());
        networkInfo.addProperty("connections", 1);
        networkInfo.addProperty("localservices", "0000000000000000");
        networkInfo.addProperty("relayfee", relayFee());
        return RpcResponse.ok(networkInfo);
    }

    private JsonObject fees() {
        JsonObject fees = new JsonObject();
        fees.addProperty("feeperbyte", WalletHelper.getFeePerByte(coin.getNetworkParameters()));
        fees.addProperty("mintxfee", WalletHelper.getMinTxFee(coin.getNetworkParameters()));
        return RpcResponse.ok(fees);
    }

    private JsonObject blockchainInfo() {
        JsonObject blockchainInfo = new JsonObject();
        blockchainInfo.addProperty("chain", coin.getNetworkParameters().getPaymentProtocolId());
        blockchainInfo.addProperty("blocks", chainHeight());
        blockchainInfo.addProperty("headers", chainHeight());
        blockchainInfo.addProperty("verificationprogress", 1.0);
        blockchainInfo.addProperty("difficulty", 0.0);
        blockchainInfo.addProperty("initialblockdownload", false);
        blockchainInfo.addProperty("pruned", false);
        return RpcResponse.ok(blockchainInfo);
    }

    private int protocolVersion() {
        return coin.getNetworkParameters()
                .getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT);
    }

    /** The per-kilobyte relay fee, in whole coins, as a Bitcoin Core node reports it. */
    private BigDecimal relayFee() {
        long feeSats = WalletHelper.getFeePerByte(coin.getNetworkParameters()) * 1000;
        long coinUnit = coinFactor();
        return new BigDecimal(feeSats)
                .divide(new BigDecimal(coinUnit), Sats.decimalPlaces(coinUnit), RoundingMode.DOWN);
    }

    private long coinFactor() {
        return CoinConfigRegistry.get(CoinTickerUtils.tickerToString(coin.getTicker()))
                .coinFactor();
    }
}
