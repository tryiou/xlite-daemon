package io.cloudchains.app.net.api.http.server.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonElement;
import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.Version;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.net.api.http.client.HTTPClient;
import io.cloudchains.app.util.AddressBalance;
import io.cloudchains.app.util.ConfigHelper;
import io.cloudchains.app.util.UTXO;
import io.cloudchains.app.util.Utility;
import io.cloudchains.app.wallet.WalletHelper;
import org.bitcoinj.core.*;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.apache.commons.codec.binary.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

// Define a helper class for output entries
class OutputEntry {
	public String address;
	public double amount;

	public OutputEntry(String address, double amount) {
		this.address = address;
		this.amount = amount;
	}
}

public class RpcHandler {
	private final static LogManager LOGMANAGER = LogManager.getLogManager();
	private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

	private HTTPClient httpClient;
	private CoinInstance coin;
	private ConfigHelper configHelper;

	public RpcHandler(CoinInstance coin) {
		this.coin = coin;
		this.configHelper = coin.getConfigHelper();
		this.httpClient = new HTTPClient(5);
	}

	public JsonObject handleReloadConfig() {
		JsonObject response = new JsonObject();
		
		Runnable r = () -> {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			coin.reloadConfig();
		};
		new Thread(r).start();

		response.addProperty("result", true);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleGetInfo() {
		JsonObject response = new JsonObject();
		JsonObject infoJSON = new JsonObject();

		infoJSON.addProperty("protocolversion", coin.getNetworkParameters().getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT));
		infoJSON.addProperty("ticker", CoinTickerUtils.tickerToString(coin.getTicker()));
		infoJSON.addProperty("balance", coin.getAllBalances());
		infoJSON.addProperty("testnet", coin.isTestnet());
		infoJSON.addProperty("difficulty", 0.0);
		infoJSON.addProperty("connections", 1);
		infoJSON.addProperty("blocks", CoinInstance.getBlockCountByTicker(coin.getTicker()));
		infoJSON.addProperty("keypoolsize", coin.getAddressKeyPairs().size());
		infoJSON.addProperty("keypoololdest", 0.0);

		BigDecimal relayFeeDecimal = new BigDecimal(coin.getConfigHelper().getFee()).setScale(8, BigDecimal.ROUND_DOWN);
		infoJSON.addProperty("relayfee", relayFeeDecimal);

		infoJSON.addProperty("networkactive", true);
		infoJSON.addProperty("timeoffset", 0);
		infoJSON.addProperty("rpcready", coin.isInstanceRunning());

		response.add("result", infoJSON);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleGetBlockCount() {
		JsonObject response = new JsonObject();
		response.addProperty("result", CoinInstance.getBlockCountByTicker(coin.getTicker()));
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleGetNetworkInfo() {
		JsonObject response = new JsonObject();
		JsonObject networkInfoJSON = new JsonObject();
		networkInfoJSON.addProperty("protocolversion", coin.getNetworkParameters().getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT));
		networkInfoJSON.addProperty("ticker", CoinTickerUtils.tickerToString(coin.getTicker()));
		networkInfoJSON.addProperty("subversion", CoinInstance.getVersionString());
		networkInfoJSON.addProperty("connections", 1);
		networkInfoJSON.addProperty("localservices", "0000000000000000");

		double relayFee = CoinInstance.getRelayFeeByTicker(coin.getTicker());
		if (relayFee == -1) {
			relayFee = coin.getConfigHelper().getFee();
		}

		BigDecimal relayFeeDecimal = BigDecimal.valueOf(relayFee).setScale(8, BigDecimal.ROUND_DOWN);
		networkInfoJSON.addProperty("relayfee", relayFeeDecimal);

		response.add("result", networkInfoJSON);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleListUnspent() {
		JsonObject response = new JsonObject();
		
		JsonArray unspent = httpClient.getUtxos(coin.getTicker(), 30000);
		if (unspent == null) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject err = new JsonObject();
			err.addProperty("message", "listunspent not ready");
			err.addProperty("code", 3002);
			response.add("error", err);
			return response;
		}
		response.add("result", unspent);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleListTransactions(JsonArray params) {
		JsonObject response = new JsonObject();
		
		int startTime = 0;
		int endTime = 0;
		if (params.size() == 2) {
			startTime = params.get(0).getAsInt();
			endTime = params.get(1).getAsInt();
		}
		JsonArray transactions = httpClient.getHistory(coin.getTicker(), startTime, endTime, 60 * 15);
		if (transactions == null) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject err = new JsonObject();
			err.addProperty("message", "listtransactions not ready");
			err.addProperty("code", 3002);
			response.add("error", err);
			return response;
		}
		response.add("result", transactions);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleGetBlockchainInfo() {
		JsonObject response = new JsonObject();
		JsonObject blockchainInfoJSON = new JsonObject();

		blockchainInfoJSON.addProperty("chain", coin.getNetworkParameters().getPaymentProtocolId());
		blockchainInfoJSON.addProperty("blocks", CoinInstance.getBlockCountByTicker(coin.getTicker()));
		blockchainInfoJSON.addProperty("headers", CoinInstance.getBlockCountByTicker(coin.getTicker()));
		blockchainInfoJSON.addProperty("verificationprogress", 1.0);
		blockchainInfoJSON.addProperty("difficulty", 0.0);
		blockchainInfoJSON.addProperty("initialblockdownload", false);
		blockchainInfoJSON.addProperty("pruned", false);

		response.add("result", blockchainInfoJSON);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleGetBlockHash(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 1) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: getblockhash index\n\nindex (num, required)");
			response.add("error", errorJSON);
			return response;
		}

		int blockIndex = params.get(0).getAsInt();
		JsonObject blockHash = httpClient.getBlockHash(coin.getTicker(), blockIndex);

		if (blockHash == null || blockHash.has("error") && !blockHash.get("error").isJsonNull()) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Error obtaining blockhash!");
			response.add("error", errorJSON);
			return response;
		}

		if (blockHash.has("result")) {
			response.add("result", blockHash.get("result"));
			response.add("error", JsonNull.INSTANCE);
			return response;
		}

		response.add("result", blockHash);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleGetBlock(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 1) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: getblock hash\n\nhash (string, required) - Hash of block to retrieve");
			response.add("error", errorJSON);
			return response;
		}

		String hash;
		try {
			hash = params.get(0).getAsString();
			hash = hash.replaceAll("\"", "");
		} catch (Exception e) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Error parsing JSON!");
			response.add("error", errorJSON);
			e.printStackTrace();
			return response;
		}

		JsonObject block = httpClient.getBlock(coin.getTicker(), hash, true);

		if (block == null || block.has("error") && !block.get("error").isJsonNull()) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Error while retrieving block");
			response.add("error", errorJSON);
			return response;
		}

		if (block.has("result")) {
			response.add("result", block.get("result"));
			response.add("error", JsonNull.INSTANCE);
			return response;
		}

		response.add("result", block);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleGetRawTransaction(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() > 2) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: getrawtransaction\n\ntxid (string, required) - TXID is required, verbose(optional)");
			response.add("error", errorJSON);
			return response;
		}

		String txid = params.get(0).getAsString();
		boolean verbose = false;

		if (params.size() == 2) {
			String v = params.get(1).toString();
			if (v.equalsIgnoreCase("true") || v.equals("1"))
				verbose = true;
		}

		JsonObject rawTransaction = httpClient.getRawTransaction(coin.getTicker(), txid, verbose);

		if (rawTransaction == null || rawTransaction.has("error") && !rawTransaction.get("error").isJsonNull()) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -5);
			errorJSON.addProperty("message", "No information available about transaction");
			response.add("error", errorJSON);
			return response;
		}

		if (rawTransaction.has("result")) {
			response.add("result", rawTransaction.get("result"));
			response.add("error", JsonNull.INSTANCE);
			return response;
		}

		response.add("result", rawTransaction);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleGetRawMempool(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() > 1) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: getrawmempool\n\nverbose (int, optional) - VERBOSE is optional");
			response.add("error", errorJSON);
			return response;
		}

		boolean verbose = false;

		if (params.size() == 2) {
			String v = params.get(1).toString();
			if (v.equalsIgnoreCase("true") || v.equals("1"))
				verbose = true;
		}

		JsonObject rawMempool = httpClient.getRawMempool(coin.getTicker(), verbose);

		if (rawMempool == null || rawMempool.has("error") && !rawMempool.get("error").isJsonNull()) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "No information available");
			response.add("error", errorJSON);
			return response;
		}

		if (rawMempool.has("result")) {
			response.add("result", rawMempool.get("result"));
			response.add("error", JsonNull.INSTANCE);
			return response;
		}

		response.add("result", rawMempool);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleGetTxOut(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() < 2 || params.size() > 3) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: gettxout txid n [include_mempool]\\n" + 
					"\\n" + 
					"txid (string, required) - The transaction ID\\n" + 
					"n (numeric, required) - The vout value\\n" + 
					"include_mempool (boolean, optional, default=true) - Whether to include the mempool (WARNING: This can block execution for several seconds)");
			response.add("error", errorJSON);
			return response;
		}

		String txid = params.get(0).getAsString();
		int n = params.get(1).getAsInt();
		boolean includeMempool = true;
		if (params.size() == 3) {
			includeMempool = params.get(2).getAsBoolean();
		}

		UTXO requested = this.getUtxo(Sha256Hash.wrap(txid), n);

		if (requested != null) {
			LOGGER.log(Level.FINER, "[http-server-handler] Using cached UTXO for gettxout");

			org.bitcoinj.core.UTXO utxo = requested.createUTXO();
			JsonObject resultJSON = new JsonObject();

			resultJSON.addProperty("confirmations",
					(CoinInstance.getBlockCountByTicker(coin.getTicker()) - utxo.getHeight()) + 1);
			resultJSON.addProperty("value", utxo.getValue().value / 100000000.0);

			JsonObject scriptPubKey = new JsonObject();
			scriptPubKey.addProperty("asm", utxo.getScript().toString());
			scriptPubKey.addProperty("hex", new String(Hex.encode(utxo.getScript().getProgram())));
			scriptPubKey.addProperty("reqSigs", utxo.getScript().getNumberOfSignaturesRequiredToSpend());

			Script.ScriptType type = utxo.getScript().getScriptType();
			getScriptType(scriptPubKey, type);

			JsonArray addresses = new JsonArray();
			addresses.add(utxo.getAddress());
			scriptPubKey.add("addresses", addresses);

			resultJSON.add("scriptPubKey", scriptPubKey);
			resultJSON.addProperty("coinbase", utxo.isCoinbase());

			response.add("result", resultJSON);
			response.add("error", JsonNull.INSTANCE);
			return response;
		}

		if (!includeMempool) {
			LOGGER.log(Level.FINER, "[http-server-handler] WARNING: Client requested UTXO that is not ours!");
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -5);
			errorJSON.addProperty("message", "Invalid or non-wallet transaction ID (not ours)");
			response.add("error", errorJSON);
			return response;
		}

		JsonObject transaction = null;
		int retries = includeMempool ? 5 : 1;
		for (int i = 0; i < retries; i++) {
			transaction = httpClient.getTransaction(coin.getTicker(), txid, true);
			if (transaction == null || transaction.has("result") && transaction.get("result").isJsonNull()) {
				if (i < retries - 1) {
					try {
						Thread.sleep(2000);
					} catch (Exception e) {
					}
					continue;
				}
			} else {
				break;
			}
		}

		if (transaction == null || transaction.has("error") && !transaction.get("error").isJsonNull()) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -5);
			errorJSON.addProperty("message", "Invalid transaction ID");
			response.add("error", errorJSON);
			return response;
		}

		try {
			JsonObject result = transaction.getAsJsonObject("result");
			JsonElement confirmations = result.get("confirmations");
			JsonArray vout = result.getAsJsonArray("vout");

			if (vout.size() <= n) {
				response.add("result", JsonNull.INSTANCE);
				JsonObject errorJSON = new JsonObject();
				errorJSON.addProperty("code", -5);
				errorJSON.addProperty("message", "Invalid transaction output index");
				response.add("error", errorJSON);
				return response;
			}

			JsonObject entry = vout.get(n).getAsJsonObject();
			JsonElement value = entry.get("value");
			JsonObject scriptPubKey = entry.getAsJsonObject("scriptPubKey");
			JsonElement addr = scriptPubKey.get("address");
			JsonArray addresses = scriptPubKey.getAsJsonArray("addresses");

			String address = null;
			if(addr != null) {
				address = addr.getAsString();
			} else {
				address = addresses.asList().get(0).getAsString();
			}

			boolean isOurs = false;
			for (AddressBalance addressBalance : coin.getAddressKeyPairs()) {
				String addressCheck = addressBalance.getAddress().toString();
				if (address.equals(addressCheck)) {
					isOurs = true;
					break;
				}
			}

			if (!isOurs) {
				LOGGER.log(Level.FINER, "[http-server-handler] WARNING: Client requested UTXO that cannot be ours!");
				response.add("result", JsonNull.INSTANCE);
				JsonObject errorJSON = new JsonObject();
				errorJSON.addProperty("code", -5);
				errorJSON.addProperty("message", "Invalid or non-wallet transaction ID (cannot be ours)");
				response.add("error", errorJSON);
				return response;
			}

			int count = 0;
			if (confirmations != null) {
				count = confirmations.getAsInt();
			}

			if (count > 0) {
				boolean unspent = false;
				JsonArray utxos = httpClient.getUtxosUncached(coin.getTicker(), new String[] { address });
				for (JsonElement utxo : utxos.asList()) {
					String newtxid = utxo.getAsJsonObject().get("txid").getAsString();
					int newvout = utxo.getAsJsonObject().get("vout").getAsInt();
					if (newtxid.equals(txid) && newvout == n) {
						unspent = true;
						break;
					}
				}

				if (!unspent) {
					LOGGER.log(Level.FINER, "[http-server-handler] WARNING: Client requested UTXO that was already spent!");
					response.add("result", JsonNull.INSTANCE);
					JsonObject errorJSON = new JsonObject();
					errorJSON.addProperty("code", -5);
					errorJSON.addProperty("message", "Invalid or non-wallet transaction ID (already spent)");
					response.add("error", errorJSON);
					return response;
				}
			}

			JsonObject resultJSON = new JsonObject();
			resultJSON.addProperty("confirmations", count);
			resultJSON.add("value", value);
			resultJSON.add("scriptPubKey", scriptPubKey);
			resultJSON.addProperty("coinbase", false);

			response.add("result", resultJSON);
			response.add("error", JsonNull.INSTANCE);
			return response;
		} catch (Exception e) {
			LOGGER.log(Level.FINER, "[http-server-handler] ERROR: Error while parsing transaction!");
			e.printStackTrace();

			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1010);
			errorJSON.addProperty("message", "Error while parsing transaction");
			response.add("error", errorJSON);
			return response;
		}
	}

	public JsonObject handleGetTransaction(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 1) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: gettransaction txid\n\ntxid (string, required) - The transaction ID");
			response.add("error", errorJSON);
			return response;
		}

		String txid = params.get(0).getAsString();
		JsonObject transaction = httpClient.getTransaction(coin.getTicker(), txid, true);

		if (transaction == null || transaction.has("error") && !transaction.get("error").isJsonNull()) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Error while obtaining transaction!");
			response.add("error", errorJSON);
			return response;
		}

		if (transaction.has("result")) {
			response.add("result", transaction.get("result"));
			response.add("error", JsonNull.INSTANCE);
			return response;
		}

		response.add("result", transaction);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleGetAddressesByAccount(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 1) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: getaddressesbyaccount account\n\naccount (string, required) - The account from which to grab addresses. The only account available (for now) is 'main'.");
			response.add("error", errorJSON);
			return response;
		}

		String account = params.get(0).getAsString();

		if (account.equals("main")) {
			JsonArray addresses = new JsonArray();
			for (AddressBalance addressBalance : coin.getAddressKeyPairs()) {
				addresses.add(addressBalance.getAddress().toString());
			}
			response.add("result", addresses);
			response.add("error", JsonNull.INSTANCE);
		} else {
			response.add("result", new JsonArray());
			response.add("error", JsonNull.INSTANCE);
		}
		return response;
	}

	public JsonObject handleSendRawTransaction(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 1) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: sendrawtransaction hex-tx\n\nhex-tx (string, required) - Raw transaction, hex encoded");
			response.add("error", errorJSON);
			return response;
		}

		String rawTx;
		Transaction transaction;
		try {
			rawTx = params.get(0).getAsString();
			transaction = new Transaction(coin.getNetworkParameters(), Hex.decode(rawTx));
			WalletHelper.setAsSpent(coin.getTicker(), transaction, true);
		} catch (Exception e) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Error parsing JSON!");
			response.add("error", errorJSON);
			e.printStackTrace();
			return response;
		}

		JsonObject txid = httpClient.sendRawTransaction(coin.getTicker(), rawTx);
		if (txid == null || txid.has("error") && !txid.get("error").isJsonNull()) {
			int code = -1;

			if (txid != null)
				code = txid.get("error").getAsInt();

			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", code);
			errorJSON.addProperty("message", "Error sending transaction!");
			response.add("error", errorJSON);
			return response;
		}

		if (txid.has("result")) {
			response.add("result", txid.get("result"));
			response.add("error", JsonNull.INSTANCE);
			return response;
		}

		response.add("result", txid);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleCreateRawTransaction(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() < 2 || params.size() > 3) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message",
					"Usage: createrawtransaction inputs outputs\n\ninputs (string, required) - Inputs in JSON format\nexample: [{\"txid\": \"id\", \"vout\": n}, ...]\n\noutputs (string, required) - Outputs in JSON format\nexample (legacy): {\"address1\": amount1, \"address2\": amount2, ...}\nexample (new): [{\"address\": \"address1\", \"amount\": amount1}, {\"address\": \"address1\", \"amount\": amount2}, ...]");
			response.add("error", errorJSON);
			return response;
		}

		JsonArray inputs;
		List<OutputEntry> outputEntries = new ArrayList<>();
		long locktime = 0;

		if (params.size() >= 3)
            locktime = params.get(3).getAsLong();

		try {
			inputs = params.get(0).getAsJsonArray();
			for (int i = 0; i < inputs.size(); i++) {
				JsonObject input = inputs.get(i).getAsJsonObject();
				if (!input.has("txid") || !input.has("vout"))
					throw new Exception("Bad transaction input.");
			}

			JsonElement outputsElem = params.get(1);
			if (outputsElem.isJsonArray()) {
				JsonArray outputsArray = outputsElem.getAsJsonArray();
				for (JsonElement elem : outputsArray) {
					JsonObject obj = elem.getAsJsonObject();
					if (!obj.has("address") || !obj.has("amount"))
						throw new Exception("Output entry must have 'address' and 'amount'");
					String addr = obj.get("address").getAsString();
					double amt = obj.get("amount").getAsDouble();
					outputEntries.add(new OutputEntry(addr, amt));
				}
			} else if (outputsElem.isJsonObject()) {
				JsonObject outputsObject = outputsElem.getAsJsonObject();
				for (String addr : outputsObject.keySet()) {
					double amt = outputsObject.get(addr).getAsDouble();
					outputEntries.add(new OutputEntry(addr, amt));
				}
			} else {
				throw new Exception("Invalid outputs format");
			}
		} catch (Exception e) {
			LOGGER.log(Level.FINER,
					"[http-server-handler] ERROR: Error while parsing JSON for createrawtransaction!");
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -2);
			errorJSON.addProperty("message", "Error parsing JSON");
			response.add("error", errorJSON);
			return response;
		}

		Transaction tx = new Transaction(coin.getNetworkParameters());
		if (locktime > 0 && !tx.isTimeLocked()) {
			tx.setLockTime(locktime);
		}

		boolean inputSuccess = true;
		for (int i = 0; i < inputs.size(); i++) {
			JsonObject input = inputs.get(i).getAsJsonObject();
			try {
				String txid = input.get("txid").getAsString();
				int vout = input.get("vout").getAsInt();
				tx.addInput(Sha256Hash.wrap(txid), vout, ScriptBuilder.createInputScript(null));
			} catch (Exception e) {
				LOGGER.log(Level.FINER,
						"[http-server-handler] ERROR: Error while constructing transaction (input phase)!");
				txConstructionError(response, e, "Error while constructing transaction (input phase)");
				inputSuccess = false;
				break;
			}
		}
		if (!inputSuccess)
			return response;

		boolean outputSuccess = true;

		for (OutputEntry entry : outputEntries) {
			try {
				Address address = LegacyAddress.fromBase58(coin.getNetworkParameters(), entry.address);
				Coin outputValue = Coin.valueOf((long) Math.floor(entry.amount * Coin.COIN.value));
				if (isP2SHAddress(entry.address)) {
					LOGGER.log(Level.FINER, "[http-server-handler] P2SH Address Found: " + entry.address);
					Script p2shScript = ScriptBuilder.createP2SHOutputScript(address.getHash());
					tx.addOutput(outputValue, p2shScript);
				}
			} catch (Exception e) {
				LOGGER.log(Level.FINER,
						"[http-server-handler] ERROR: Error while constructing transaction (output phase)!");
				e.printStackTrace();
				txConstructionError(response, e, "Error while constructing transaction (output phase)");
				outputSuccess = false;
				break;
			}
		}
		
		for (OutputEntry entry : outputEntries) {
			try {
				Address address = LegacyAddress.fromBase58(coin.getNetworkParameters(), entry.address);
				Coin outputValue = Coin.valueOf((long) Math.floor(entry.amount * Coin.COIN.value));
				if (!isP2SHAddress(entry.address)) {
					tx.addOutput(outputValue, address);
				}
			} catch (Exception e) {
				LOGGER.log(Level.FINER,
						"[http-server-handler] ERROR: Error while constructing transaction (output phase)!");
				e.printStackTrace();
				txConstructionError(response, e, "Error while constructing transaction (output phase)");
				outputSuccess = false;
				break;
			}
		}

		if (!outputSuccess)
			return response;

		String hexTx = new String(Hex.encode(tx.bitcoinSerialize()));
		LOGGER.log(Level.FINER, "[http-server-handler] DEBUG: Raw transaction = " + hexTx);
		response.addProperty("result", hexTx);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleDecodeRawTransaction(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 1) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: decoderawtransaction rawtx\n\nrawtx (string, required) - The raw transaction to decode, hex encoded.");
			response.add("error", errorJSON);
			return response;
		}

		String rawTx = params.get(0).getAsString();
		Transaction tx;

		try {
			tx = new Transaction(coin.getNetworkParameters(), Hex.decode(rawTx));
		} catch (Exception e) {
			e.printStackTrace();
			getInvalidTxResponse(response, e);
			return response;
		}

		JsonObject txJSON = new JsonObject();

		txJSON.addProperty("txid", tx.getHashAsString());
		txJSON.addProperty("version", tx.getVersion());
		txJSON.addProperty("locktime", tx.getLockTime());
		JsonArray vin = new JsonArray();

		boolean inputParseSuccess = true;

		for (TransactionInput input : tx.getInputs()) {
			try {
				JsonObject thisVin = new JsonObject();
				thisVin.addProperty("txid", input.getOutpoint().getHash().toString());
				thisVin.addProperty("vout", input.getOutpoint().getIndex());

				JsonObject scriptSig = new JsonObject();
				scriptSig.addProperty("asm", canonicalizeASM(input.getScriptSig().toString()));
				scriptSig.addProperty("hex", new String(Hex.encode(input.getScriptSig().getProgram())));

				thisVin.add("scriptSig", scriptSig);
				thisVin.addProperty("sequence", input.getSequenceNumber());

				vin.add(thisVin);
			} catch (Exception e) {
				LOGGER.log(Level.FINER, "[http-server-handler] ERROR: Error while parsing transaction inputs!");
				e.printStackTrace();

				response.add("result", JsonNull.INSTANCE);
				JsonObject errorJSON = new JsonObject();
				errorJSON.addProperty("code", -1008);
				errorJSON.addProperty("message", "Error while parsing transaction inputs");
				response.add("error", errorJSON);
				inputParseSuccess = false;
			}
		}

		if (!inputParseSuccess)
			return response;

		txJSON.add("vin", vin);
		JsonArray vout = new JsonArray();

		for (TransactionOutput output : tx.getOutputs()) {
			try {
				Script.ScriptType type = output.getScriptPubKey().getScriptType();

				JsonObject thisVout = new JsonObject();
				thisVout.addProperty("value", (double) output.getValue().value / 100000000.0);
				thisVout.addProperty("n", output.getIndex());

				JsonObject scriptPubKey = new JsonObject();
				scriptPubKey.addProperty("asm", canonicalizeASM(output.getScriptPubKey().toString()));
				scriptPubKey.addProperty("hex", new String(Hex.encode(output.getScriptPubKey().getProgram())));

				if (type == Script.ScriptType.P2SH) {
					scriptPubKey.addProperty("reqSigs", 1);
				} else {
					scriptPubKey.addProperty("reqSigs", output.getScriptPubKey().getNumberOfSignaturesRequiredToSpend());
				}

				getScriptType(scriptPubKey, type);

				JsonArray addresses = new JsonArray();
				addresses.add(output.getScriptPubKey().getToAddress(coin.getNetworkParameters()).toString());
				scriptPubKey.add("addresses", addresses);
				thisVout.add("scriptPubKey", scriptPubKey);
				vout.add(thisVout);
			} catch (Exception e) {
				LOGGER.log(Level.FINER, "[http-server-handler] ERROR: Error while parsing transaction outputs!");
				e.printStackTrace();

				response.add("result", JsonNull.INSTANCE);
				JsonObject errorJSON = new JsonObject();
				errorJSON.addProperty("code", -1009);
				errorJSON.addProperty("message", "Error while parsing transaction outputs");
				response.add("error", errorJSON);
			}
		}

		txJSON.add("vout", vout);
		response.add("result", txJSON);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleSignRawTransaction(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() < 1 || params.size() > 3) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: signrawtransaction rawtx\n\nrawtx (string, required) - The raw transaction to sign, hex encoded.");
			response.add("error", errorJSON);
			return response;
		}

		String rawTx = params.get(0).getAsString();
		Transaction tx;

		try {
			tx = new Transaction(coin.getNetworkParameters(), Hex.decode(rawTx));
		} catch (Exception e) {
			e.printStackTrace();
			getInvalidTxResponse(response, e);
			return response;
		}

		Transaction signedTx = new Transaction(coin.getNetworkParameters());
		boolean complete = true;

		for (TransactionOutput output : tx.getOutputs()) {
			signedTx.addOutput(output);
		}

		for (TransactionInput input : tx.getInputs()) {
			Sha256Hash txid = input.getOutpoint().getHash();
			long vout = input.getOutpoint().getIndex();

			ECKey signingKey = getSigningKey(txid, vout);
			UTXO utxo = getUtxo(txid, vout);

			if (utxo == null || signingKey == null) {
				getInvalidTxResponse(response, new Exception("Transaction contains an utxo/input which does not exist in our wallet."));
				return response;
			}

			org.bitcoinj.core.UTXO bUtxo = utxo.createUTXO();
			TransactionOutPoint outPoint = new TransactionOutPoint(coin.getNetworkParameters(), bUtxo.getIndex(), bUtxo.getHash());
			signedTx.addSignedInput(outPoint, bUtxo.getScript(), signingKey, Transaction.SigHash.ALL, true);
		}

		String signedTxHex = new String(Hex.encode(signedTx.bitcoinSerialize()));
		JsonObject resultJSON = new JsonObject();
		resultJSON.addProperty("hex", signedTxHex);
		resultJSON.addProperty("complete", complete);

		response.add("result", resultJSON);
		response.add("error", JsonNull.INSTANCE);
		LOGGER.log(Level.FINER, "[DEBUG http-server-handler] Signed Raw Transaction: " + response.toString());
		return response;
	}

	public JsonObject handleSendTransaction(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 2) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: sendtransaction address amount\n\naddress (string, required)\namount (number, required)");
			response.add("error", errorJSON);
			return response;
		}

		String address;
		double amount;
		try {
			address = params.get(0).getAsString();
			amount = params.get(1).getAsDouble();
		} catch (Exception e) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Error parsing JSON!");
			response.add("error", errorJSON);
			e.printStackTrace();
			return response;
		}

		Transaction transaction;
		try {
			transaction = WalletHelper.createTransactionSimple(coin.getTicker(), address, amount);
			WalletHelper.setAsSpent(coin.getTicker(), transaction, true);
		} catch (Exception e) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Error while creating transaction!");
			response.add("error", errorJSON);
			e.printStackTrace();
			return response;
		}

		JsonObject txid = httpClient.sendRawTransaction(coin.getTicker(), new String(Hex.encode(transaction.bitcoinSerialize())));
		if (txid == null || txid.has("error") && !txid.get("error").isJsonNull()) {
			int code = -1;

			if (txid != null)
				code = txid.get("error").getAsInt();

			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", code);
			errorJSON.addProperty("message", "Error sending transaction!");
			response.add("error", errorJSON);
			return response;
		}

		if (txid.has("result")) {
			response.add("result", txid.get("result"));
			response.add("error", JsonNull.INSTANCE);
			return response;
		}

		response.add("result", txid);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleGetNewAddress(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 0) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: getnewaddress");
			response.add("error", errorJSON);
			return response;
		}

		AddressBalance newAddress = coin.generateAddress(true);
		response.addProperty("result", newAddress.getAddress().toString());
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleImportPrivKey(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 1) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: importprivkey privatekey\n\nNOTE: Key's are not persistent!\n\nprivatekey (string, required)");
			response.add("error", errorJSON);
			return response;
		}

		coin.importPrivateKey(params.get(0).getAsString());
		response.addProperty("result", "");
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleDumpPrivKey(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 1) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: dumpprivkey address\n\naddress (string, required)");
			response.add("error", errorJSON);
			return response;
		}

		AddressBalance addressBalance = coin.getAddress(params.get(0).getAsString());

		if (addressBalance == null) {
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -4);
			errorJSON.addProperty("message", "Address does not exist");
			response.add("result", JsonNull.INSTANCE);
			response.add("error", errorJSON);
			return response;
		}

		response.addProperty("result", addressBalance.getPrivateKey().toString());
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleSignMessage(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 2) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: signmessage address message\n\naddress (string, required) - The address whose private key to use to sign the message\nmessage (string, required) - The message to sign");
			response.add("error", errorJSON);
			return response;
		}

		String addr = params.get(0).getAsString();
		String message = params.get(1).getAsString();

		AddressBalance address = coin.getAddressBalance(addr);
		if (address == null) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -5);
			errorJSON.addProperty("message", "Invalid or non-wallet address");
			response.add("error", errorJSON);
			return response;
		}

		ECKey key = address.getPrivateKey().getKey();
		String signatureB64 = signMessage(key, message);

		response.addProperty("result", signatureB64);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleVerifyMessage(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 3) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: verifymessage address signature message\n\naddress (string, required) - The address whose private key to use to sign the message\nsignature (string, required) - The signature to verify, base64 encoded\nmessage (string, required) - The message to sign");
			response.add("error", errorJSON);
			return response;
		}

		String addr = params.get(0).getAsString();
		String signatureB64 = params.get(1).getAsString();
		String message = params.get(2).getAsString();

		boolean verified = false;

		try {
			ECKey key = signedMessageToKey(signatureB64, message);
			verified = verifyMessage(key, signatureB64, message);
			if (!verified)
				throw new SignatureException("Signature was not verified.");

			String derivedAddr = new LegacyAddress(coin.getNetworkParameters(), key.getPubKeyHash()).toString();
			if (!addr.equals(derivedAddr)) {
				LOGGER.log(Level.FINER, "[http-server-handler] ERROR: Addresses do not match! Failing.");
				verified = false;
			}
		} catch (Exception e) {
			LOGGER.log(Level.FINER, "[http-server-handler] Error while verifying signature! Invalid signature?");
			e.printStackTrace();
			response.addProperty("result", verified);
			response.add("error", JsonNull.INSTANCE);
			return response;
		}

		response.addProperty("result", verified);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleVersion() {
		JsonObject response = new JsonObject();
		response.addProperty("result", Version.CLIENT_VERSION);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleValidateAddress(JsonArray params) {
		JsonObject response = new JsonObject();
		
		if (params.size() != 1) {
			response.add("result", JsonNull.INSTANCE);
			JsonObject errorJSON = new JsonObject();
			errorJSON.addProperty("code", -1);
			errorJSON.addProperty("message", "Usage: validateaddress address\n\naddress (string, required) - The address to validate.");
			response.add("error", errorJSON);
			return response;
		}

		JsonObject resultJSON = new JsonObject();
		String address = params.get(0).getAsString();

		boolean isValidAddress = Utility.isValidAddress(coin.getNetworkParameters(), address);
		resultJSON.addProperty("isvalid", isValidAddress);
		resultJSON.addProperty("address", address);

		boolean isP2SH = false;
		String scriptPubKey = "";
		if (isValidAddress) {
			Address toAddress = LegacyAddress.fromBase58(coin.getNetworkParameters(), address);
			if (isP2SHAddress(address)) {
				isP2SH = true;
				TransactionOutput output = new TransactionOutput(coin.getNetworkParameters(), null, Coin.valueOf(0), toAddress);
				scriptPubKey = new String(Hex.encode(output.getScriptPubKey().getProgram()));
			}
		}

		resultJSON.addProperty("scriptPubKey", scriptPubKey);
		resultJSON.addProperty("isscript", isP2SH);

		response.add("result", resultJSON);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleHelp() {
		JsonObject response = new JsonObject();
		
		String helpString = "JSON-RPC server for " + CoinTickerUtils.tickerToString(coin.getTicker()) + "\n"
				+ "This JSON-RPC server is served by " + CoinInstance.getVersionString() + "\n"
				+ "\n"
				+ "help - This command help.\n"
				+ "version - Get version\n"
				+ "\n=====Blockchain=====\n"
				+ "gettxout <txid> <vout> - Get info about an unspent transaction output\n"
				+ "\n=====Network=====\n"
				+ "getinfo - Get information such as balances, protocol version, and more.\n"
				+ "getblockcount - Get block count\n"
				+ "getnetworkinfo - Get network information\n"
				+ "getrawmempool - Get raw mempool\n"
				+ "getblockchaininfo - Get blockchain info\n"
				+ "getblockhash <height> - Get the hash of a block at a given height\n"
				+ "getblock <hash> - Get a block's JSON representation given its hash\n"
				+ "\n=====Wallet=====\n"
				+ "listunspent - Get all UTXOs in the wallet\n"
				+ "listtransactions - Get all transactions in the wallet\n"
				+ "getnewaddress - Generate a new address\n"
				+ "gettransaction <txid> - Get a transaction given its TXID\n"
				+ "getaddressesbyaccount <account> - Get addresses belonging to a given account. The only account available is 'main' which contains all addresses.\n"
				+ "importprivkey <privkey> - Import an address given it's privkey\n"
				+ "dumpprivkey <address> - Dump an addresses private key\n"
				+ "\n=====Utilities=====\n"
				+ "signmessage <address> <message> - Sign a message with a given address' private key\n"
				+ "verifymessage <address> <signature> <message> - Verify a signature for a message signed by a given address\n"
				+ "validateaddress <address> - Validate a given address\n"
				+ "sendtransaction <address> <amount> - Create and broadcast a signed transaction to the network\n"
				+ "\n=====Raw Transactions=====\n"
				+ "createrawtransaction <inputs> <outputs> - Create a raw transaction given inputs and outputs in JSON format. For more info, run createrawtransaction with no arguments.\n"
				+ "decoderawtransaction <rawtx> - Get a raw transaction's JSON representation\n"
				+ "signrawtransaction <rawtx> - Sign a raw transaction\n"
				+ "sendrawtransaction <rawtx> - Broadcast a signed raw transaction to the network\n";

		response.addProperty("result", helpString);
		response.add("error", JsonNull.INSTANCE);
		return response;
	}

	public JsonObject handleDefault() {
		JsonObject response = new JsonObject();
		JsonObject methodNotFound = new JsonObject();
		methodNotFound.addProperty("code", -32601);
		methodNotFound.addProperty("message", "Method not found.");
		response.add("error", methodNotFound);
		response.add("result", JsonNull.INSTANCE);
		return response;
	}

	// Helper methods
	private String canonicalizeASM(String asm) {
		return asm
				.replaceAll("DUP", "OP_DUP")
				.replaceAll("HASH160", "OP_HASH160")
				.replaceAll("EQUALVERIFY", "OP_EQUALVERIFY")
				.replaceAll("CHECKSIG", "OP_CHECKSIG")
				.replaceAll("RETURN", "OP_RETURN")
				.replaceAll("PUSHDATA", "")
				.replaceAll("\\[", "")
				.replaceAll("]", "")
				.replaceAll("\\([0-9]+\\)", "");
	}

	private byte[] formatMessageForSigning(String message) {
		String header = null;

		switch (coin.getTicker()) {
			case BLOCKNET:
			case BLOCKNET_TESTNET5:
				header = "Blocknet Signed Message:\n";
				break;
			case BITCOIN:
				header = "Bitcoin Signed Message:\n";
				break;
			case LITECOIN:
				header = "Litecoin Signed Message:\n";
				break;
			case PIVX:
				header = "DarkNet Signed Message:\n";
				break;
			case DASHCOIN:
				header = "DarkCoin Signed Message:\n";
				break;
			case UNOBTANIUM:
				header = "Unobtanium Signed Message:\n";
				break;
			case DOGECOIN:
				header = "Dogecoin Signed Message:\n";
				break;
			case SYSCOIN:
				header = "Syscoin Signed Message:\n";
				break;
			default:
				LOGGER.log(Level.FINER, "[http-server-handler] ERROR: Unsupported coin. This should never happen.");
				break;
		}

		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
			bos.write(headerBytes.length);
			bos.write(headerBytes);
			byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
			VarInt size = new VarInt(messageBytes.length);
			bos.write(size.encode());
			bos.write(messageBytes);
			return bos.toByteArray();
		} catch (IOException e) {
			LOGGER.log(Level.FINER, "[http-server-handler] Error while formatting message for signing!");
			e.printStackTrace();
		}
		return null;
	}

	private String signMessage(ECKey key, String message) {
		byte[] formatted = formatMessageForSigning(message);
		if (formatted == null) {
			return null;
		}
		Sha256Hash hash = Sha256Hash.twiceOf(formatted);
		ECKey.ECDSASignature signature = key.sign(hash);
		byte recoveryId = -1;
		for (int i = 0; i < 4; i++) {
			ECKey k = ECKey.recoverFromSignature(i, signature, hash, key.isCompressed());
			if (k != null && java.util.Arrays.equals(k.getPubKey(), key.getPubKey())) {
				recoveryId = (byte) i;
				break;
			}
		}
		if (recoveryId == -1)
			throw new IllegalStateException("Recovery ID is invalid.");

		byte[] sigData = new byte[65];
		byte headerByte = (byte) (recoveryId + 27 + (key.isCompressed() ? 4 : 0));
		sigData[0] = headerByte;
		System.arraycopy(org.bitcoinj.core.Utils.bigIntegerToBytes(signature.r, 32), 0, sigData, 1, 32);
		System.arraycopy(org.bitcoinj.core.Utils.bigIntegerToBytes(signature.s, 32), 0, sigData, 33, 32);
		return new String(Base64.encodeBase64String(sigData));
	}

	private ECKey signedMessageToKey(String signatureB64, String message) throws SignatureException {
		byte[] signatureEncoded;
		try {
			signatureEncoded = Base64.decodeBase64(signatureB64);
		} catch (RuntimeException e) {
			throw new SignatureException("Could not decode base64", e);
		}

		if (signatureEncoded.length < 65)
			throw new SignatureException("Signature truncated, expected 65 bytes and got " + signatureEncoded.length);
		int header = signatureEncoded[0] & 0xFF;

		if (header < 27 || header > 34)
			throw new SignatureException("Header byte out of range: " + header);

		BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(signatureEncoded, 1, 33));
		BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(signatureEncoded, 33, 65));
		ECKey.ECDSASignature sig = new ECKey.ECDSASignature(r, s);
		byte[] messageBytes = formatMessageForSigning(message);
		if (messageBytes == null) {
			return null;
		}
		Sha256Hash messageHash = Sha256Hash.twiceOf(messageBytes);
		boolean compressed = false;
		if (header >= 31) {
			compressed = true;
			header -= 4;
		}
		int recId = header - 27;
		ECKey key = ECKey.recoverFromSignature(recId, sig, messageHash, compressed);
		if (key == null)
			throw new SignatureException("Could not recover public key from signature");
		return key;
	}

	private boolean verifyMessage(ECKey key, String signatureB64, String message) {
		boolean verified = false;
		try {
			ECKey k = signedMessageToKey(signatureB64, message);
			if (java.util.Arrays.equals(k.getPubKey(), key.getPubKey()))
				verified = true;
		} catch (SignatureException e) {
			LOGGER.log(Level.FINER, "[http-server-handler] ERROR: Error while verifying message. Invalid signature?");
			e.printStackTrace();
		}
		return verified;
	}

	private boolean isP2SHAddress(String address) {
		byte[] versionAndDataBytes = org.bitcoinj.core.Base58.decodeChecked(address);
		int version = versionAndDataBytes[0] & 0xFF;
		return coin.getNetworkParameters().getP2SHHeader() == version;
	}

	private void getScriptType(JsonObject scriptPubKey, Script.ScriptType type) {
		String typeStr = "unknown";

		switch (type) {
			case P2PKH:
				typeStr = "pubkeyhash";
				break;
			case P2SH:
				typeStr = "scripthash";
				break;
			default:
				break;
		}

		scriptPubKey.addProperty("type", typeStr);
	}

	private UTXO getUtxo(Sha256Hash txid, long vout) {
		for (AddressBalance addressBalance : coin.getAddressKeyPairs()) {
			for (UTXO utxo : addressBalance.getUtxos()) {
				if (utxo.createUTXO().getHash().equals(txid) && utxo.getVout() == vout) {
					return utxo;
				}
			}
		}
		return null;
	}

	private ECKey getSigningKey(Sha256Hash txid, long vout) {
		for (AddressBalance addressBalance : coin.getAddressKeyPairs()) {
			for (UTXO utxo : addressBalance.getUtxos()) {
				if (utxo.createUTXO().getHash().equals(txid) && utxo.getVout() == vout) {
					return addressBalance.getPrivateKey().getKey();
				}
			}
		}
		return null;
	}

	private void getInvalidTxResponse(JsonObject response, Exception e) {
		LOGGER.log(Level.FINER, "[http-server-handler] ERROR: Error while decoding raw tx!");
		e.printStackTrace();

		response.add("result", JsonNull.INSTANCE);
		JsonObject errorJSON = new JsonObject();
		errorJSON.addProperty("code", -1007);
		errorJSON.addProperty("message", "Error decoding raw tx. Invalid transaction?");

		response.add("error", errorJSON);
	}

	private void txConstructionError(JsonObject response, Exception e, String s) {
		e.printStackTrace();

		response.add("result", JsonNull.INSTANCE);
		JsonObject errorJSON = new JsonObject();
		errorJSON.addProperty("code", -1006);
		errorJSON.addProperty("message", s);
		response.add("error", errorJSON);
	}
}
