package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.subgraph.orchid.encoders.Hex;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.electrumx.HistoryEngine;
import io.xlite.daemon.app.util.AddressBalance;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.core.TransactionOutput;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Wallet methods: addresses, keys and local history. */
final class WalletMethods {

    private final CoinInstance coin;

    WalletMethods(CoinInstance coin) {
        this.coin = coin;
    }

    Map<String, RpcMethod> methods() {
        return Map.of(
                "listtransactions", this::listTransactions,
                "historystatus", this::historyStatus,
                "getaddressesbyaccount", this::addressesByAccount,
                "getnewaddress", this::newAddress,
                "importprivkey", this::importPrivateKey,
                "dumpprivkey", this::dumpPrivateKey,
                "validateaddress", this::validateAddress);
    }

    private JsonObject listTransactions(JsonArray params) {
        int startTime = 0;
        int endTime = 0;
        if (params.size() == 2) {
            startTime = params.get(0).getAsInt();
            endTime = params.get(1).getAsInt();
        }
        HistoryEngine engine = coin.getHistoryEngine();
        if (engine == null || !engine.isReady()) {
            return RpcResponse.error(3002, "listtransactions not ready");
        }
        return RpcResponse.ok(filterHistory(coin.getAllTransactions(), startTime, endTime));
    }

    /** Time-window view of the in-memory snapshot; pending rows are always included. */
    private static JsonArray filterHistory(JsonArray rows, int startTime, int endTime) {
        JsonArray filtered = new JsonArray();
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            int time = row.has("time") ? row.get("time").getAsInt() : 0;
            int confirmations = row.has("confirmations") ? row.get("confirmations").getAsInt() : 0;
            boolean pending = confirmations == 0;
            if (pending || endTime <= 0 || (time >= startTime && time <= endTime)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private JsonObject historyStatus(JsonArray params) {
        JsonObject status = new JsonObject();
        HistoryEngine engine = coin.getHistoryEngine();
        status.addProperty("native", engine != null);
        if (engine == null) {
            status.addProperty("ready", false);
            return RpcResponse.ok(status);
        }
        HistoryEngine.Status snapshot = engine.status();
        status.addProperty("ready", snapshot.ready());
        status.addProperty("addresses", snapshot.addresses());
        status.addProperty("loaded", snapshot.loaded());
        status.addProperty("rows", snapshot.rows());
        status.addProperty("tip", snapshot.tip());
        return RpcResponse.ok(status);
    }

    private JsonObject addressesByAccount(JsonArray params) {
        if (params.size() != 1) {
            return RpcResponse.usage(
                    """
                    Usage: getaddressesbyaccount account

                    account (string, required) - The account from which to grab addresses. \
                    The only account available (for now) is 'main'.\
                    """);
        }
        if (!"main".equals(params.get(0).getAsString())) {
            return RpcResponse.ok(new JsonArray());
        }
        JsonArray addresses = new JsonArray();
        for (AddressBalance balance : coin.getAddressKeyPairs()) {
            addresses.add(balance.getAddress().toBase58());
        }
        return RpcResponse.ok(addresses);
    }

    private JsonObject newAddress(JsonArray params) {
        if (!params.isEmpty()) {
            return RpcResponse.usage("Usage: getnewaddress");
        }
        return RpcResponse.ok(coin.generateAddress(true).getAddress().toBase58());
    }

    private JsonObject importPrivateKey(JsonArray params) {
        if (params.size() != 1) {
            return RpcResponse.usage(
                    """
                    Usage: importprivkey privatekey

                    NOTE: Key's are not persistent!

                    privatekey (string, required)\
                    """);
        }
        coin.importPrivateKey(params.get(0).getAsString());
        return RpcResponse.ok("");
    }

    private JsonObject dumpPrivateKey(JsonArray params) {
        if (params.size() != 1) {
            return RpcResponse.usage("Usage: dumpprivkey address\n\naddress (string, required)");
        }
        AddressBalance address = coin.getAddressBalance(params.get(0).getAsString());
        if (address == null) {
            return RpcResponse.error(-4, "Address does not exist");
        }
        return RpcResponse.ok(address.getPrivateKey().toBase58());
    }

    private JsonObject validateAddress(JsonArray params) {
        if (params.size() != 1) {
            return RpcResponse.usage(
                    "Usage: validateaddress address\n\n"
                            + "address (string, required) - The address to validate.");
        }
        String address = params.get(0).getAsString();
        Address parsed = coin.parseAddress(address);
        boolean valid = parsed != null;
        AddressScript script = valid ? scriptOf(parsed) : new AddressScript("", false);

        JsonObject result = new JsonObject();
        result.addProperty("isvalid", valid);
        result.addProperty("address", address);
        result.addProperty("scriptPubKey", script.hex());
        result.addProperty("isscript", script.payToScriptHash());
        return RpcResponse.ok(result);
    }

    /**
     * The script an address pays to, and whether that script is a P2SH one.
     *
     * <p>{@code coin.parseAddress} already decoded every accepted spelling — base58check, bech32
     * and a chain-specific alias such as CashAddr — so this method only has to turn the decoded
     * address into its output script. Both legacy kinds have one: {@code P2PKH} pays to {@code
     * OP_DUP OP_HASH160 <hash> OP_EQUALVERIFY OP_CHECKSIG}, {@code P2SH} to {@code OP_HASH160
     * <hash> OP_EQUAL}. The decoded address decides which, so the two share one path.
     *
     * <p>The segwit branch is unreachable for the coins this daemon serves: none sets a bech32 HRP,
     * so {@code parseAddress} rejects a bech32 string before it gets here. It is kept because the
     * method is total over bitcoinj's address types, not because a current coin exercises it.
     */
    private AddressScript scriptOf(Address parsed) {
        if (parsed instanceof SegwitAddress segwit) {
            return new AddressScript(witnessScript(segwit), false);
        }
        LegacyAddress legacy = (LegacyAddress) parsed;
        TransactionOutput output =
                new TransactionOutput(coin.getNetworkParameters(), null, Coin.valueOf(0), legacy);
        return new AddressScript(
                hex(output.getScriptPubKey().getProgram()), legacy.isP2SHAddress());
    }

    /**
     * The witness program of a segwit address as an {@code OP_<version> <len> <program>} script.
     */
    private static String witnessScript(SegwitAddress address) {
        byte[] program = address.getWitnessProgram();
        int version = address.getWitnessVersion();
        String opVersion = version == 0 ? "00" : Integer.toHexString(0x50 + version);
        String pushLength = String.format("%02x", program.length);
        return opVersion + pushLength + hex(program);
    }

    private static String hex(byte[] bytes) {
        return new String(Hex.encode(bytes), StandardCharsets.UTF_8);
    }

    /** The script an address pays to, in hex, and whether it is a script hash. */
    private record AddressScript(String hex, boolean payToScriptHash) {}
}
