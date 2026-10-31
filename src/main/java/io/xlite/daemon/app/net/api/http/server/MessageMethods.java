package io.xlite.daemon.app.net.api.http.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.xlite.daemon.app.net.CoinInstance;
import io.xlite.daemon.app.net.CoinTickerUtils;
import io.xlite.daemon.app.util.AddressBalance;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;

import java.security.SignatureException;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/** Message signing and verification with a wallet address. */
final class MessageMethods {

    private static final LogManager LOGMANAGER = LogManager.getLogManager();
    private static final Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private final CoinInstance coin;

    MessageMethods(CoinInstance coin) {
        this.coin = coin;
    }

    Map<String, RpcMethod> methods() {
        return Map.of(
                "signmessage", this::signMessage,
                "verifymessage", this::verifyMessage);
    }

    private JsonObject signMessage(JsonArray params) {
        if (params.size() != 2) {
            return RpcResponse.usage(
                    "Usage: signmessage address message\n\n"
                            + "address (string, required) - The address whose private key to use"
                            + " to sign the message\n"
                            + "message (string, required) - The message to sign");
        }
        String address = params.get(0).getAsString();
        String message = params.get(1).getAsString();

        AddressBalance balance = coin.getAddressBalance(address);
        if (balance == null) {
            return RpcResponse.error(-5, "Invalid or non-wallet address");
        }
        ECKey key = balance.getPrivateKey().getKey();
        return RpcResponse.ok(SignedMessages.sign(coin.getTicker(), key, message));
    }

    /**
     * Verifies a signature the way Bitcoin wallets do: recover the key from the signature and check
     * that it controls the claimed address. The recovered key is not separately checked against
     * itself, which would only restate that recovery succeeded.
     */
    private JsonObject verifyMessage(JsonArray params) {
        if (params.size() != 3) {
            return RpcResponse.usage(
                    "Usage: verifymessage address signature message\n\n"
                            + "address (string, required) - The address whose private key to use"
                            + " to sign the message\n"
                            + "signature (string, required) - The signature to verify, base64"
                            + " encoded\n"
                            + "message (string, required) - The message to sign");
        }
        String address = params.get(0).getAsString();
        String signature = params.get(1).getAsString();
        String message = params.get(2).getAsString();

        boolean verified;
        try {
            ECKey key = SignedMessages.recoverKey(coin.getTicker(), signature, message);
            String derived = LegacyAddress.fromKey(coin.getNetworkParameters(), key).toBase58();
            verified = address.equals(derived);
            if (!verified) {
                LOGGER.warning("[http-server-handler] ERROR: Addresses do not match! Failing.");
            }
        } catch (SignatureException e) {
            LOGGER.warning(
                    "[http-server-handler] Error verifying message for "
                            + CoinTickerUtils.tickerToString(coin.getTicker())
                            + ", "
                            + e.getMessage());
            verified = false;
        }
        return RpcResponse.ok(verified);
    }
}
