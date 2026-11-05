package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.xlite.daemon.app.Version;
import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;

import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/** Server-level methods: liveness, version, help and configuration reload. */
final class ServerMethods {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private static final String HELP_BODY =
            "\n\n"
                + "help - This command help.\n"
                + "version - Get version\n"
                + "\n"
                + "=====Blockchain=====\n"
                + "gettxout <txid> <vout> - Get info about an unspent transaction output\n"
                + "\n"
                + "=====Network=====\n"
                + "ping - Lightweight liveness probe (result 1 if RPC listener alive)\n"
                + "getinfo - Get information such as balances, protocol version, and more.\n"
                + "getblockcount - Get block count\n"
                + "getnetworkinfo - Get network information\n"
                + "getfees - Get fee per byte and minimum transaction fee\n"
                + "getrawmempool - Get raw mempool\n"
                + "getblockchaininfo - Get blockchain info\n"
                + "getblockhash <height> - Get the hash of a block at a given height\n"
                + "getblock <hash> - Get a block's JSON representation given its hash\n"
                + "\n"
                + "=====Wallet=====\n"
                + "listunspent - Get all UTXOs in the wallet\n"
                + "listtransactions [start] [end] - Get all transactions in the wallet\n"
                + "historystatus - Get the history snapshot: readiness, tip and refused addresses\n"
                + "getnewaddress - Generate a new address\n"
                + "gettransaction <txid> - Get a transaction given its TXID\n"
                + "getaddressesbyaccount <account> - Get addresses belonging to a given account."
                + " The only account available is 'main' which contains all addresses.\n"
                + "importprivkey <privkey> - Import an address given it's privkey\n"
                + "dumpprivkey <address> - Dump an addresses private key\n"
                + "\n"
                + "=====Utilities=====\n"
                + "signmessage <address> <message> - Sign a message with a given address' private"
                + " key\n"
                + "verifymessage <address> <signature> <message> - Verify a signature for a message"
                + " signed by a given address\n"
                + "validateaddress <address> - Validate a given address\n"
                + "reloadconfig <ticker> - Reload configuration for the specified coin ticker\n"
                + "sendtransaction <address> <amount> [dryrun] - Create and broadcast a signed"
                + " transaction to the network\n"
                + "\n"
                + "=====Raw Transactions=====\n"
                + "getrawtransaction <txid> [verbose] - Get a transaction's serialized hex (JSON"
                + " when verbose)\n"
                + "createrawtransaction <inputs> <outputs> - Create a raw transaction given inputs"
                + " and outputs in JSON format. For more info, run createrawtransaction with no"
                + " arguments.\n"
                + "decoderawtransaction <rawtx> - Get a raw transaction's JSON representation\n"
                + "signrawtransaction <rawtx> - Sign a raw transaction\n"
                + "sendrawtransaction <rawtx> - Broadcast a signed raw transaction to the"
                + " network\n";

    private final CoinInstance coin;

    ServerMethods(CoinInstance coin) {
        this.coin = coin;
    }

    Map<String, RpcMethod> methods() {
        return Map.of(
                "reloadconfig",
                this::reloadConfig,
                "version",
                params -> RpcResponse.ok(Version.CLIENT_VERSION),
                "ping",
                params -> RpcResponse.ok(1L),
                "help",
                this::help);
    }

    /**
     * Reloads this coin's configuration after a short delay, so the caller gets its response before
     * the coin's state is replaced.
     */
    private JsonObject reloadConfig(JsonArray params) {
        Thread reloader =
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        LOGGER.warning(
                                "[http-server-handler] Interrupted during reloadconfig for "
                                        + CoinTickerUtils.tickerToString(coin.getTicker())
                                        + ", "
                                        + e.getMessage());
                        Thread.currentThread().interrupt();
                    }
                    coin.reloadConfig();
                });
        reloader.setDaemon(true);
        reloader.start();
        return RpcResponse.ok(true);
    }

    private JsonObject help(JsonArray params) {
        return RpcResponse.ok(
                "JSON-RPC server for "
                        + CoinTickerUtils.tickerToString(coin.getTicker())
                        + "\nThis JSON-RPC server is served by "
                        + CoinInstance.getVersionString()
                        + HELP_BODY);
    }
}
